// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/apu/kiwi_aaudio/aaudio_audio_driver.h"

#include <algorithm>
#include <cstring>
#include <thread>

#include "xenia/apu/apu_flags.h"
#include "xenia/apu/conversion.h"
#include "xenia/base/assert.h"
#include "xenia/base/byte_order.h"
#include "xenia/base/logging.h"

namespace xe {
namespace apu {
namespace kiwi_aaudio {

namespace {
// Enough for the audio worker's queue plus the XMP player's, without
// allocating in the data callback in practice.
constexpr size_t kReservedFrames = 32;
}  // namespace

AAudioDriver::AAudioDriver(xe::threading::Semaphore* semaphore,
                           uint32_t frequency, uint32_t channels,
                           bool need_format_conversion)
    : semaphore_(semaphore),
      frequency_(frequency),
      channels_(channels),
      need_format_conversion_(need_format_conversion) {
  assert_not_null(semaphore_);
  // A frame is always kFrameSamplesMax samples, whatever the channel count.
  channel_samples_ = channels_ ? kFrameSamplesMax / channels_ : 0;
  unused_.reserve(kReservedFrames);
}

AAudioDriver::~AAudioDriver() { Shutdown(); }

bool AAudioDriver::Initialize() {
  if (channels_ != 2 && channels_ != 6) {
    XELOGE("AAudio: {} channel guest audio isn't supported", channels_);
    return false;
  }
  if (need_format_conversion_ && channels_ != 6) {
    XELOGE("AAudio: format conversion expects 6 channels, got {}", channels_);
    return false;
  }
  shutting_down_ = false;
  std::lock_guard<std::mutex> lock(stream_mutex_);
  return OpenStream();
}

bool AAudioDriver::OpenStream() {
  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t result = AAudio_createStreamBuilder(&builder);
  if (result != AAUDIO_OK) {
    XELOGE("AAudio: createStreamBuilder failed: {}",
           AAudio_convertResultToText(result));
    return false;
  }
  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setChannelCount(builder, kOutputChannels);
  // The system resamples if the device runs at another rate.
  AAudioStreamBuilder_setSampleRate(builder, int32_t(frequency_));
  AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
  AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
  AAudioStreamBuilder_setDataCallback(builder, DataCallback, this);
  AAudioStreamBuilder_setErrorCallback(builder, ErrorCallback, this);
  AAudioStream* stream = nullptr;
  result = AAudioStreamBuilder_openStream(builder, &stream);
  AAudioStreamBuilder_delete(builder);
  if (result != AAUDIO_OK) {
    XELOGE("AAudio: openStream failed: {}", AAudio_convertResultToText(result));
    return false;
  }
  stream_ = stream;
  XELOGI(
      "AAudio: output stream open, {} Hz (guest {} Hz), {} channels, burst {} "
      "frames, buffer {}/{} frames, performance mode {}, sharing mode {}",
      AAudioStream_getSampleRate(stream), frequency_,
      AAudioStream_getChannelCount(stream),
      AAudioStream_getFramesPerBurst(stream),
      AAudioStream_getBufferSizeInFrames(stream),
      AAudioStream_getBufferCapacityInFrames(stream),
      int(AAudioStream_getPerformanceMode(stream)),
      int(AAudioStream_getSharingMode(stream)));
  if (!paused_) {
    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
      XELOGE("AAudio: requestStart failed: {}",
             AAudio_convertResultToText(result));
      AAudioStream_close(stream);
      stream_ = nullptr;
      return false;
    }
  }
  return true;
}

void AAudioDriver::CloseStream() {
  if (stream_) {
    AAudioStream_requestStop(stream_);
    // Waits for a data callback in progress to return.
    AAudioStream_close(stream_);
    stream_ = nullptr;
  }
}

void AAudioDriver::Shutdown() {
  shutting_down_ = true;
  {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    CloseStream();
  }
  // A restart in progress gives up once it sees shutting_down_.
  while (restarting_) {
    std::this_thread::yield();
  }
  std::lock_guard<std::mutex> lock(frames_mutex_);
  queued_.clear();
  unused_.clear();
  front_played_ = 0;
}

void AAudioDriver::SubmitFrame(float* frame) {
  std::vector<float> converted;
  {
    std::lock_guard<std::mutex> lock(frames_mutex_);
    if (!unused_.empty()) {
      converted = std::move(unused_.back());
      unused_.pop_back();
    }
  }
  converted.resize(size_t(channel_samples_) * kOutputChannels);
  float* out = converted.data();
  if (need_format_conversion_) {
    conversion::sequential_6_BE_to_interleaved_2_LE(out, frame,
                                                    channel_samples_);
  } else if (channels_ == 2) {
    std::memcpy(out, frame, sizeof(float) * channel_samples_ * 2);
  } else {
    // Interleaved 5.1 in host order: the same downmix as the converted path.
    for (uint32_t i = 0; i < channel_samples_; ++i) {
      const float* s = frame + size_t(i) * 6;
      const float common = s[2] * 0.707106781f + s[3] * 0.5f;
      out[i * 2 + 0] = s[0] + common + s[4] * 0.707106781f;
      out[i * 2 + 1] = s[1] + common + s[5] * 0.707106781f;
    }
  }
  std::lock_guard<std::mutex> lock(frames_mutex_);
  queued_.push_back(std::move(converted));
}

void AAudioDriver::Pause() {
  std::lock_guard<std::mutex> lock(stream_mutex_);
  paused_ = true;
  if (stream_) {
    AAudioStream_requestPause(stream_);
  }
}

void AAudioDriver::Resume() {
  std::lock_guard<std::mutex> lock(stream_mutex_);
  paused_ = false;
  if (stream_) {
    AAudioStream_requestStart(stream_);
  }
}

aaudio_data_callback_result_t AAudioDriver::DataCallback(AAudioStream* stream,
                                                         void* user_data,
                                                         void* audio_data,
                                                         int32_t num_frames) {
  static_cast<AAudioDriver*>(user_data)->Fill(static_cast<float*>(audio_data),
                                              num_frames);
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioDriver::Fill(float* out, int32_t num_frames) {
  const uint32_t master = std::min(cvars::volume, uint32_t(100));
  const float volume = volume_.load(std::memory_order_relaxed) * (master / 100.0f);
  uint32_t frames_played = 0;
  size_t remaining = size_t(num_frames);
  {
    std::lock_guard<std::mutex> lock(frames_mutex_);
    while (remaining && !queued_.empty()) {
      std::vector<float>& front = queued_.front();
      const size_t available = channel_samples_ - front_played_;
      const size_t count = std::min(available, remaining);
      const float* in = front.data() + front_played_ * kOutputChannels;
      for (size_t i = 0; i < count * kOutputChannels; ++i) {
        out[i] = in[i] * volume;
      }
      out += count * kOutputChannels;
      remaining -= count;
      front_played_ += count;
      if (front_played_ == channel_samples_) {
        front_played_ = 0;
        unused_.push_back(std::move(front));
        queued_.pop_front();
        ++frames_played;
      }
    }
  }
  if (remaining) {
    // Underrun: the guest hasn't produced enough yet.
    std::memset(out, 0, remaining * kOutputChannels * sizeof(float));
  }
  if (frames_played) {
    semaphore_->Release(int(frames_played), nullptr);
  }
}

void AAudioDriver::ErrorCallback(AAudioStream* stream, void* user_data,
                                 aaudio_result_t error) {
  auto driver = static_cast<AAudioDriver*>(user_data);
  XELOGW("AAudio: stream error: {}", AAudio_convertResultToText(error));
  if (error != AAUDIO_ERROR_DISCONNECTED || driver->shutting_down_) {
    return;
  }
  // The stream can't be closed from its own callback thread.
  bool expected = false;
  if (driver->restarting_.compare_exchange_strong(expected, true)) {
    std::thread([driver]() { driver->RestartAfterDisconnect(); }).detach();
  }
}

void AAudioDriver::RestartAfterDisconnect() {
  {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (!shutting_down_) {
      CloseStream();
      if (OpenStream()) {
        XELOGI("AAudio: output reopened after the device changed");
      }
    }
  }
  restarting_ = false;
}

}  // namespace kiwi_aaudio
}  // namespace apu
}  // namespace xe

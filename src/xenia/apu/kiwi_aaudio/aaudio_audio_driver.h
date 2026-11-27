// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_APU_KIWI_AAUDIO_AAUDIO_AUDIO_DRIVER_H_
#define XENIA_APU_KIWI_AAUDIO_AAUDIO_AUDIO_DRIVER_H_

#include <aaudio/AAudio.h>

#include <atomic>
#include <deque>
#include <memory>
#include <mutex>
#include <vector>

#include "xenia/apu/audio_driver.h"
#include "xenia/base/threading.h"

namespace xe {
namespace apu {
namespace kiwi_aaudio {

// Plays the guest's audio frames through an Android AAudio output stream, in
// stereo float. Frames are converted when submitted (big-endian 5.1 with one
// block per channel -> interleaved little-endian stereo), and the stream's
// data callback pulls them, releasing the guest's semaphore for each frame it
// has fully played, so the device's clock paces the guest's audio. On an
// underrun it plays silence. A lost stream (output device changed) is opened
// again on another thread.
class AAudioDriver : public AudioDriver {
 public:
  AAudioDriver(xe::threading::Semaphore* semaphore,
               uint32_t frequency = kFrameFrequencyDefault,
               uint32_t channels = kFrameChannelsDefault,
               bool need_format_conversion = true);
  ~AAudioDriver() override;

  bool Initialize() override;
  void Shutdown() override;
  void SubmitFrame(float* frame) override;
  void Pause() override;
  void Resume() override;
  void SetVolume(float volume) override { volume_ = volume; }

 private:
  static constexpr int32_t kOutputChannels = 2;

  bool OpenStream();
  void CloseStream();
  void RestartAfterDisconnect();

  static aaudio_data_callback_result_t DataCallback(AAudioStream* stream,
                                                    void* user_data,
                                                    void* audio_data,
                                                    int32_t num_frames);
  static void ErrorCallback(AAudioStream* stream, void* user_data,
                            aaudio_result_t error);
  void Fill(float* out, int32_t num_frames);

  xe::threading::Semaphore* semaphore_;
  uint32_t frequency_;
  uint32_t channels_;
  uint32_t channel_samples_;
  bool need_format_conversion_;
  std::atomic<float> volume_ = 1.0f;

  // Guards the stream pointer against the restart thread.
  std::mutex stream_mutex_;
  AAudioStream* stream_ = nullptr;
  bool paused_ = false;
  std::atomic<bool> shutting_down_ = false;
  std::atomic<bool> restarting_ = false;

  // Converted frames, each channel_samples_ stereo sample pairs.
  std::mutex frames_mutex_;
  std::deque<std::vector<float>> queued_;
  std::vector<std::vector<float>> unused_;
  // Stereo samples of queued_.front() already played.
  size_t front_played_ = 0;
};

}  // namespace kiwi_aaudio
}  // namespace apu
}  // namespace xe

#endif  // XENIA_APU_KIWI_AAUDIO_AAUDIO_AUDIO_DRIVER_H_

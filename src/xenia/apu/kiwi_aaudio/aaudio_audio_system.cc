// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/apu/kiwi_aaudio/aaudio_audio_system.h"

#include "xenia/apu/kiwi_aaudio/aaudio_audio_driver.h"
#include "xenia/apu/nop_audio_driver.h"
#include "xenia/base/assert.h"
#include "xenia/base/logging.h"

namespace xe {
namespace apu {
namespace kiwi_aaudio {

std::unique_ptr<AudioSystem> AAudioAudioSystem::Create(
    cpu::Processor* processor) {
  return std::make_unique<AAudioAudioSystem>(processor);
}

AAudioAudioSystem::AAudioAudioSystem(cpu::Processor* processor)
    : AudioSystem(processor) {}

AAudioAudioSystem::~AAudioAudioSystem() = default;

X_STATUS AAudioAudioSystem::CreateDriver(size_t index,
                                         xe::threading::Semaphore* semaphore,
                                         AudioDriver** out_driver) {
  assert_not_null(out_driver);
  auto driver = std::make_unique<AAudioDriver>(semaphore);
  if (driver->Initialize()) {
    *out_driver = driver.release();
    return X_STATUS_SUCCESS;
  }
  driver->Shutdown();
  // Without an output the game must still run: discard its audio instead.
  XELOGW("AAudio: no output stream for client {}, its audio is discarded",
         index);
  *out_driver = new nop::NopAudioDriver(semaphore);
  return X_STATUS_SUCCESS;
}

AudioDriver* AAudioAudioSystem::CreateDriver(
    xe::threading::Semaphore* semaphore, uint32_t frequency, uint32_t channels,
    bool need_format_conversion) {
  return new AAudioDriver(semaphore, frequency, channels,
                          need_format_conversion);
}

}  // namespace kiwi_aaudio
}  // namespace apu
}  // namespace xe

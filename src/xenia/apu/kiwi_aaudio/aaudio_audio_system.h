// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_APU_KIWI_AAUDIO_AAUDIO_AUDIO_SYSTEM_H_
#define XENIA_APU_KIWI_AAUDIO_AAUDIO_AUDIO_SYSTEM_H_

#include "xenia/apu/audio_system.h"

namespace xe {
namespace apu {
namespace kiwi_aaudio {

// Android audio output through AAudio (apu = "aaudio").
class AAudioAudioSystem : public AudioSystem {
 public:
  explicit AAudioAudioSystem(cpu::Processor* processor);
  ~AAudioAudioSystem() override;

  static bool IsAvailable() { return true; }

  static std::unique_ptr<AudioSystem> Create(cpu::Processor* processor);

  std::string name() const override { return "AAudio"; }

  X_STATUS CreateDriver(size_t index, xe::threading::Semaphore* semaphore,
                        AudioDriver** out_driver) override;
  AudioDriver* CreateDriver(xe::threading::Semaphore* semaphore,
                            uint32_t frequency, uint32_t channels,
                            bool need_format_conversion) override;
};

}  // namespace kiwi_aaudio
}  // namespace apu
}  // namespace xe

#endif  // XENIA_APU_KIWI_AAUDIO_AAUDIO_AUDIO_SYSTEM_H_

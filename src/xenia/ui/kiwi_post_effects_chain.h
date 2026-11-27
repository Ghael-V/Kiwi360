// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_UI_KIWI_POST_EFFECTS_CHAIN_H_
#define XENIA_UI_KIWI_POST_EFFECTS_CHAIN_H_

#include <cstdint>
#include <string>

namespace xe {
namespace ui {

// The image effects chain set while the game runs (the in-game menu), handed
// to the presenter from any thread. Generation 0 means nothing was published:
// the kiwi_post_effects cvar as set at launch applies.
void KiwiPublishPostEffects(const std::string& chain);
uint32_t KiwiPublishedPostEffectsGeneration();
std::string KiwiPublishedPostEffects();

}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_KIWI_POST_EFFECTS_CHAIN_H_

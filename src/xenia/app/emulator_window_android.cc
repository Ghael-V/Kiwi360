/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

// Kiwi360 prototype: Android implementation of EmulatorWindow. The desktop
// emulator_window.cc is built on wxWidgets, which Android doesn't have. This
// keeps the parts the emulator core and the ImGui dialogs need (window, ImGui
// drawer, presenter painting, display cvars) - copied unchanged from
// emulator_window.cc - and leaves the desktop UI (menus, game list, dialogs)
// as no-ops. Measurement harness, not a product UI.

#include "xenia/app/emulator_window.h"

#include <string>

#include "version.h"
#include "xenia/base/assert.h"
#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"
#include "xenia/base/profiling.h"
#include "xenia/base/threading.h"
#include "xenia/gpu/graphics_system.h"
#include "xenia/ui/immediate_drawer.h"
#include "xenia/ui/window.h"

// Complete the wx-only types the header holds by unique_ptr.
class wxTimer {};

DECLARE_bool(debug);
DECLARE_path(target);
DECLARE_bool(in_process_title_relaunch);
DECLARE_bool(discord);

DECLARE_string(hid);
DECLARE_string(gpu);
DECLARE_string(apu);

DECLARE_string(config);

DEFINE_transient_bool(return_to_ui, false,
                      "Return to UI process when game exits. Set automatically "
                      "when launching from UI.",
                      "General");

DEFINE_bool(boot_to_dashboard, false,
            "Start the installed dashboard after the boot animation instead of "
            "showing the game list, and go back to the dashboard when a title "
            "exits.",
            "General");

DEFINE_bool(fullscreen, false, "Whether to launch the emulator in fullscreen.",
            "Display");

DEFINE_bool(guide_button, true,
            "Toggle the context menu with the guide button.", "UI");

DEFINE_string(
    postprocess_antialiasing, "",
    "Post-processing anti-aliasing effect to apply to the image output of the "
    "game.\n"
    "Using post-process anti-aliasing is heavily recommended when AMD "
    "FidelityFX Contrast Adaptive Sharpening or Super Resolution 1.0 is "
    "active.\n"
    "Use: [none, fxaa, fxaa_extreme]\n"
    " none (or any value not listed here):\n"
    "  Don't alter the original image.\n"
    " fxaa:\n"
    "  NVIDIA Fast Approximate Anti-Aliasing 3.11, normal quality preset (12)."
    "\n"
    " fxaa_extreme:\n"
    "  NVIDIA Fast Approximate Anti-Aliasing 3.11, extreme quality preset "
    "(39).",
    "Display");
DEFINE_string(
    postprocess_scaling_and_sharpening, "",
    "Post-processing effect to use for resampling and/or sharpening of the "
    "final display output.\n"
    "Use: [bilinear, cas, fsr]\n"
    " bilinear (or any value not listed here):\n"
    "  Original image at 1:1, simple bilinear stretching for resampling.\n"
    " cas:\n"
    "  Use AMD FidelityFX Contrast Adaptive Sharpening (CAS) for sharpening "
    "at scaling factors of up to 2x2, with additional bilinear stretching for "
    "larger factors.\n"
    " fsr:\n"
    "  Use AMD FidelityFX Super Resolution 1.0 (FSR) for highest-quality "
    "upscaling, or AMD FidelityFX Contrast Adaptive Sharpening for sharpening "
    "while not scaling or downsampling.\n"
    "  For scaling by factors of more than 2x2, multiple FSR passes are done.",
    "Display");
DEFINE_double(
    postprocess_ffx_cas_additional_sharpness,
    xe::ui::Presenter::GuestOutputPaintConfig::kCasAdditionalSharpnessDefault,
    "Additional sharpness for AMD FidelityFX Contrast Adaptive Sharpening "
    "(CAS), from 0 to 1.\n"
    "Higher is sharper.",
    "Display");
DEFINE_uint32(
    postprocess_ffx_fsr_max_upsampling_passes,
    xe::ui::Presenter::GuestOutputPaintConfig::kFsrMaxUpscalingPassesMax,
    "Maximum number of upsampling passes performed in AMD FidelityFX Super "
    "Resolution 1.0 (FSR) before falling back to bilinear stretching after the "
    "final pass.\n"
    "Each pass upscales only to up to 2x2 the previous size. If the game "
    "outputs a 1280x720 image, 1 pass will upscale it to up to 2560x1440 "
    "(below 4K), after 2 passes it will be upscaled to a maximum of 5120x2880 "
    "(including 3840x2160 for 4K), and so on.\n"
    "This variable has no effect if the display resolution isn't very high, "
    "but may be reduced on resolutions like 4K or 8K in case the performance "
    "impact of multiple FSR upsampling passes is too high, or if softer edges "
    "are desired.\n"
    "The default value is the maximum internally supported by Kiwi360.",
    "Display");
DEFINE_double(
    postprocess_ffx_fsr_sharpness_reduction,
    xe::ui::Presenter::GuestOutputPaintConfig::kFsrSharpnessReductionDefault,
    "Sharpness reduction for AMD FidelityFX Super Resolution 1.0 (FSR), in "
    "stops.\n"
    "Lower is sharper.",
    "Display");
// Dithering to 8bpc is enabled by default since the effect is minor, only
// effects what can't be shown normally by host displays, and nothing is changed
// by it for 8bpc source without resampling.
DEFINE_bool(
    postprocess_dither, true,
    "Dither the final image output from the internal precision to 8 bits per "
    "channel so gradients are smoother.\n"
    "On a 10bpc display, the lower 2 bits will still be kept, but noise will "
    "be added to them - disabling may be recommended for 10bpc, but it "
    "depends on the 10bpc displaying capabilities of the actual display used.",
    "Display");

#if XE_PLATFORM_LINUX
DEFINE_bool(
    use_mangohud, false,
    "Enable MangoHUD overlay when launching games in separate processes.\n"
    "MangoHUD must be installed on your system for this to work.",
    "Linux");
DEFINE_bool(use_gamemode, false,
            "Use Feral GameMode when launching games in separate processes.\n"
            "GameMode must be installed on your system for this to work.",
            "Linux");
#endif
#if XE_PLATFORM_MAC
DEFINE_bool(
    use_metal_hud, false,
    "Enable the Metal performance HUD (MTL_HUD_ENABLED=1) when launching "
    "games in separate processes.",
    "MacOS");
DEFINE_bool(
    use_rosetta, false,
    "Launch games under Rosetta 2 (x86_64) instead of native arm64.\n"
    "Only meaningful on Apple Silicon hosts running a universal app bundle.",
    "MacOS");
#endif
DEFINE_bool(disable_game_window_mouse, false,
            "Disables mouse interactions in the game window, including "
            "double-click to fullscreen, the right-click context menu and "
            "mouse input to the dashboard.",
            "General");
DEFINE_bool(offer_title_updates, false,
            "Offers to install a title update for a game when it's launched.",
            "General");

namespace xe {
namespace app {

struct WxToolbarState {};

constexpr std::string_view kBaseTitle = "Kiwi360";

EmulatorWindow::EmulatorWindow(Emulator* emulator,
                               ui::WindowedAppContext& app_context,
                               uint32_t width, uint32_t height)
    : emulator_(emulator),
      app_context_(app_context),
      window_listener_(*this),
      window_(ui::Window::Create(app_context, kBaseTitle, width, height)),
      imgui_drawer_(
          std::make_unique<ui::ImGuiDrawer>(window_.get(), kZOrderImGui)) {
  base_title_ = std::string(kBaseTitle) +
#ifdef DEBUG
#if _NO_DEBUG_HEAP == 1
                " DEBUG"
#else
                " CHECKED"
#endif
#endif
                " ("
#ifdef XE_BUILD_IS_PR
                "PR#" XE_BUILD_PR_NUMBER " - "
#endif
                XE_BUILD_BRANCH "@" XE_BUILD_COMMIT_SHORT " on " XE_BUILD_DATE
                ")";
}

std::unique_ptr<EmulatorWindow> EmulatorWindow::Create(
    Emulator* emulator, ui::WindowedAppContext& app_context, uint32_t width,
    uint32_t height) {
  assert_true(app_context.IsInUIThread());
  std::unique_ptr<EmulatorWindow> emulator_window(
      new EmulatorWindow(emulator, app_context, width, height));
  if (!emulator_window->Initialize()) {
    return nullptr;
  }
  return emulator_window;
}

EmulatorWindow::~EmulatorWindow() {
  library_fill_alive_->store(false);

  // Stop the gamepad poll thread and wait for it to exit; it touches members of
  // this window and Thread::reset() does not join.
  gamepad_poll_running_ = false;
  if (gamepad_poll_thread_) {
    xe::threading::Wait(gamepad_poll_thread_.get(), false);
    gamepad_poll_thread_.reset();
  }

  // Notify the ImGui drawer that the immediate drawer is being destroyed.
  ShutdownGraphicsSystemPresenterPainting();
}

ui::Presenter* EmulatorWindow::GetGraphicsSystemPresenter() const {
  gpu::GraphicsSystem* graphics_system = emulator_->graphics_system();
  return graphics_system ? graphics_system->presenter() : nullptr;
}

void EmulatorWindow::SetupGraphicsSystemPresenterPainting() {
  if (!window_) {
    return;
  }

  ui::Presenter* presenter = GetGraphicsSystemPresenter();
  // Skip if already wired — rebuilding mid-stream wedges Vulkan's swap chain.
  if (presenter && presenter_painting_ == presenter) {
    return;
  }

  ShutdownGraphicsSystemPresenterPainting();

  if (!presenter) {
    return;
  }

  ApplyDisplayConfigForCvars();

  window_->SetPresenter(presenter);
  presenter_painting_ = presenter;

  immediate_drawer_ =
      emulator_->graphics_system()->provider()->CreateImmediateDrawer();
  if (immediate_drawer_) {
    immediate_drawer_->SetPresenter(presenter);
    imgui_drawer_->SetPresenterAndImmediateDrawer(presenter,
                                                  immediate_drawer_.get());
    Profiler::SetUserIO(kZOrderProfiler, window_.get(), presenter,
                        immediate_drawer_.get());
  }
}

void EmulatorWindow::ShutdownGraphicsSystemPresenterPainting() {
  Profiler::SetUserIO(kZOrderProfiler, window_.get(), nullptr, nullptr);
  imgui_drawer_->SetPresenterAndImmediateDrawer(nullptr, nullptr);
  immediate_drawer_.reset();
  if (window_) {
    window_->SetPresenter(nullptr);
  }
  presenter_painting_ = nullptr;
}

bool EmulatorWindow::Initialize() {
  if (!window_) {
    return false;
  }
  window_->AddListener(&window_listener_);
  window_->AddInputListener(&window_listener_, kZOrderEmulatorWindowInput);
  if (!window_->Open()) {
    XELOGE("Kiwi360: failed to open the Android window");
    return false;
  }
  return true;
}

void EmulatorWindow::OnEmulatorInitialized() {
  emulator_initialized_ = true;
  XELOGI("Kiwi360: emulator initialized");
}

void EmulatorWindow::UpdateTitle() {}

void EmulatorWindow::SetInitializingShaderStorage(bool initializing) {
  initializing_shader_storage_ = initializing;
  XELOGI("Kiwi360: shader storage initializing = {}", initializing);
}

void EmulatorWindow::SetCompilingShaders(bool compiling) {
  compiling_shaders_ = compiling;
}

void EmulatorWindow::ShowMissingXeFuFile(const std::string& file) {
  XELOGE("Kiwi360: missing XeFu file {}", file);
}

const char* EmulatorWindow::GetCvarValueForSwapPostEffect(
    gpu::CommandProcessor::SwapPostEffect effect) {
  switch (effect) {
    case gpu::CommandProcessor::SwapPostEffect::kFxaa:
      return "fxaa";
    case gpu::CommandProcessor::SwapPostEffect::kFxaaExtreme:
      return "fxaa_extreme";
    default:
      return "";
  }
}

gpu::CommandProcessor::SwapPostEffect
EmulatorWindow::GetSwapPostEffectForCvarValue(const std::string& cvar_value) {
  if (cvar_value == GetCvarValueForSwapPostEffect(
                        gpu::CommandProcessor::SwapPostEffect::kFxaa)) {
    return gpu::CommandProcessor::SwapPostEffect::kFxaa;
  }
  if (cvar_value == GetCvarValueForSwapPostEffect(
                        gpu::CommandProcessor::SwapPostEffect::kFxaaExtreme)) {
    return gpu::CommandProcessor::SwapPostEffect::kFxaaExtreme;
  }
  return gpu::CommandProcessor::SwapPostEffect::kNone;
}

const char* EmulatorWindow::GetCvarValueForGuestOutputPaintEffect(
    ui::Presenter::GuestOutputPaintConfig::Effect effect) {
  switch (effect) {
    case ui::Presenter::GuestOutputPaintConfig::Effect::kCas:
      return "cas";
    case ui::Presenter::GuestOutputPaintConfig::Effect::kFsr:
      return "fsr";
    default:
      return "";
  }
}

ui::Presenter::GuestOutputPaintConfig::Effect
EmulatorWindow::GetGuestOutputPaintEffectForCvarValue(
    const std::string& cvar_value) {
  if (cvar_value == GetCvarValueForGuestOutputPaintEffect(
                        ui::Presenter::GuestOutputPaintConfig::Effect::kCas)) {
    return ui::Presenter::GuestOutputPaintConfig::Effect::kCas;
  }
  if (cvar_value == GetCvarValueForGuestOutputPaintEffect(
                        ui::Presenter::GuestOutputPaintConfig::Effect::kFsr)) {
    return ui::Presenter::GuestOutputPaintConfig::Effect::kFsr;
  }
  return ui::Presenter::GuestOutputPaintConfig::Effect::kBilinear;
}

ui::Presenter::GuestOutputPaintConfig
EmulatorWindow::GetGuestOutputPaintConfigForCvars() {
  ui::Presenter::GuestOutputPaintConfig paint_config;
  paint_config.SetAllowOverscanCutoff(true);
  paint_config.SetEffect(GetGuestOutputPaintEffectForCvarValue(
      cvars::postprocess_scaling_and_sharpening));
  paint_config.SetCasAdditionalSharpness(
      float(cvars::postprocess_ffx_cas_additional_sharpness));
  paint_config.SetFsrMaxUpsamplingPasses(
      cvars::postprocess_ffx_fsr_max_upsampling_passes);
  paint_config.SetFsrSharpnessReduction(
      float(cvars::postprocess_ffx_fsr_sharpness_reduction));
  paint_config.SetDither(cvars::postprocess_dither);
  return paint_config;
}

void EmulatorWindow::ApplyDisplayConfigForCvars() {
  gpu::GraphicsSystem* graphics_system = emulator_->graphics_system();
  if (!graphics_system) {
    return;
  }

  gpu::CommandProcessor* command_processor =
      graphics_system->command_processor();
  if (command_processor) {
    command_processor->SetDesiredSwapPostEffect(
        GetSwapPostEffectForCvarValue(cvars::postprocess_antialiasing));
  }

  ui::Presenter* presenter = graphics_system->presenter();
  if (presenter) {
    presenter->SetGuestOutputPaintConfigFromUIThread(
        GetGuestOutputPaintConfigForCvars());
  }
}

void EmulatorWindow::UpdateAntiAliasingCvar(
    gpu::CommandProcessor::SwapPostEffect effect) {
  if (GetSwapPostEffectForCvarValue(cvars::postprocess_antialiasing) !=
      effect) {
    OVERRIDE_PERSIST_string(postprocess_antialiasing,
                            GetCvarValueForSwapPostEffect(effect));
  }
}

void EmulatorWindow::UpdateScalingAndSharpeningCvar(
    ui::Presenter::GuestOutputPaintConfig::Effect effect) {
  auto cvars_config = GetGuestOutputPaintConfigForCvars();
  if (cvars_config.GetEffect() != effect) {
    OVERRIDE_PERSIST_string(postprocess_scaling_and_sharpening,
                            GetCvarValueForGuestOutputPaintEffect(effect));
  }
}

void EmulatorWindow::UpdateFsrSharpnessCvar(float value) {
  auto cvars_config = GetGuestOutputPaintConfigForCvars();
  if (cvars_config.GetFsrSharpnessReduction() != value) {
    OVERRIDE_PERSIST_double(postprocess_ffx_fsr_sharpness_reduction, value);
  }
}

void EmulatorWindow::UpdateCasSharpnessCvar(float value) {
  auto cvars_config = GetGuestOutputPaintConfigForCvars();
  if (cvars_config.GetCasAdditionalSharpness() != value) {
    OVERRIDE_PERSIST_double(postprocess_ffx_cas_additional_sharpness, value);
  }
}

void EmulatorWindow::UpdateDitherCvar(bool value) {
  auto cvars_config = GetGuestOutputPaintConfigForCvars();
  if (cvars_config.GetDither() != value) {
    OVERRIDE_PERSIST_bool(postprocess_dither, value);
  }
}

void EmulatorWindow::UpdateFsrMaxUpsamplingPassesCvar(uint32_t value) {
  auto cvars_config = GetGuestOutputPaintConfigForCvars();
  if (cvars_config.GetFsrMaxUpsamplingPasses() != value) {
    OVERRIDE_PERSIST_uint32(postprocess_ffx_fsr_max_upsampling_passes, value);
  }
}

void EmulatorWindow::EmulatorWindowListener::OnClosing(ui::UIEvent& e) {
  emulator_window_.app_context().QuitFromUIThread();
}
void EmulatorWindow::EmulatorWindowListener::OnFileDrop(ui::FileDropEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnResize(ui::UISetupEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnKeyDown(ui::KeyEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnMouseDown(ui::MouseEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnMouseUp(ui::MouseEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnMouseDoubleClick(
    ui::MouseEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnMouseMove(ui::MouseEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnMouseWheel(ui::MouseEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnLostFocus(ui::UISetupEvent& e) {}
void EmulatorWindow::EmulatorWindowListener::OnUsbDeviceChanged(
    bool is_arrival) {}

}  // namespace app
}  // namespace xe

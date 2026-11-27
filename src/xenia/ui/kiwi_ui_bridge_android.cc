// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: the Java side of the UI bridge is EmulatorActivity, which shows the
// requests in a Compose overlay (dev.kiwi360.frontend.overlay) and answers
// through nativeUiRespond. JSON travels as UTF-8 byte arrays: JNI's string
// functions use modified UTF-8, which mangles characters outside the BMP.

#include <jni.h>

#include <atomic>
#include <mutex>
#include <unordered_map>

#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"
#include "xenia/base/main_android.h"
#include "xenia/ui/kiwi_ui_bridge.h"

DEFINE_bool(kiwi_compose_ui, true,
            "Draw the emulator's windows (system dialogs, notifications, the "
            "crash report) with the Android frontend instead of ImGui.",
            "Kiwi360");

namespace xe {
namespace ui {
namespace kiwi_ui {

namespace {

std::mutex host_mutex;
jclass host_class = nullptr;
jmethodID host_on_request = nullptr;
std::atomic<uint64_t> next_request_id{1};
std::mutex response_mutex;
std::unordered_map<uint64_t, std::string> responses;

bool Send(uint64_t id, const std::string& json) {
  JNIEnv* env = GetAndroidThreadJniEnv();
  if (!env) {
    return false;
  }
  jclass cls;
  jmethodID method;
  {
    std::lock_guard<std::mutex> lock(host_mutex);
    if (!host_class) {
      return false;
    }
    cls = host_class;
    method = host_on_request;
  }
  jbyteArray bytes = env->NewByteArray(jsize(json.size()));
  if (!bytes) {
    env->ExceptionClear();
    return false;
  }
  env->SetByteArrayRegion(bytes, 0, jsize(json.size()),
                          reinterpret_cast<const jbyte*>(json.data()));
  env->CallStaticVoidMethod(cls, method, jlong(id), bytes);
  env->DeleteLocalRef(bytes);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return true;
}

}  // namespace

bool Available() {
  if (!cvars::kiwi_compose_ui) {
    return false;
  }
  std::lock_guard<std::mutex> lock(host_mutex);
  return host_class != nullptr;
}

uint64_t Request(const std::string& json) {
  if (!Available()) {
    return 0;
  }
  const uint64_t id = next_request_id.fetch_add(1);
  return Send(id, json) ? id : 0;
}

std::optional<std::string> TakeResponse(uint64_t id) {
  std::lock_guard<std::mutex> lock(response_mutex);
  auto it = responses.find(id);
  if (it == responses.end()) {
    return std::nullopt;
  }
  std::string response = std::move(it->second);
  responses.erase(it);
  return response;
}

bool Toast(std::string_view kind, std::string_view title,
           std::string_view text) {
  return Request(JsonWriter()
                     .Str("type", "toast")
                     .Str("kind", kind)
                     .Str("title", title)
                     .Str("text", text)
                     .Finish()) != 0;
}

bool ShowCrash(std::string_view title, std::string_view text) {
  return Request(JsonWriter()
                     .Str("type", "crash")
                     .Str("title", title)
                     .Str("text", text)
                     .Finish()) != 0;
}

}  // namespace kiwi_ui
}  // namespace ui
}  // namespace xe

extern "C" JNIEXPORT void JNICALL
Java_jp_xenia_emulator_EmulatorActivity_nativeSetUiHost(JNIEnv* env,
                                                        jclass clazz,
                                                        jboolean enabled) {
  using namespace xe::ui::kiwi_ui;
  std::lock_guard<std::mutex> lock(host_mutex);
  if (host_class) {
    env->DeleteGlobalRef(host_class);
    host_class = nullptr;
    host_on_request = nullptr;
  }
  if (enabled) {
    host_on_request =
        env->GetStaticMethodID(clazz, "onNativeUiRequest", "(J[B)V");
    if (host_on_request) {
      host_class = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    } else {
      env->ExceptionClear();
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_jp_xenia_emulator_EmulatorActivity_nativeUiRespond(JNIEnv* env,
                                                        jclass clazz, jlong id,
                                                        jbyteArray json) {
  using namespace xe::ui::kiwi_ui;
  std::string response;
  if (json) {
    const jsize length = env->GetArrayLength(json);
    response.resize(size_t(length));
    env->GetByteArrayRegion(json, 0, length,
                            reinterpret_cast<jbyte*>(response.data()));
  }
  std::lock_guard<std::mutex> lock(response_mutex);
  responses[uint64_t(id)] = std::move(response);
}

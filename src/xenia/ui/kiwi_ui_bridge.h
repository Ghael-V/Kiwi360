// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_UI_KIWI_UI_BRIDGE_H_
#define XENIA_UI_KIWI_UI_BRIDGE_H_

// Kiwi360: hands the emulator's windows (XAM dialogs, notifications, the crash
// report) to the Android frontend, which draws them in Compose over the game.
//
// A dialog keeps its whole Xenia lifecycle (XAM completion, XN_SYS_UI, input
// blocking); only its drawing moves: instead of ImGui widgets, its OnDraw sends
// one JSON request, then polls for the frontend's JSON answer and closes with
// it. Without a frontend (no Java host registered, or kiwi_compose_ui off) the
// requests are refused and the dialogs draw with ImGui as before.

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>

#include "third_party/rapidjson/include/rapidjson/document.h"
#include "third_party/rapidjson/include/rapidjson/stringbuffer.h"
#include "third_party/rapidjson/include/rapidjson/writer.h"

namespace xe {
namespace ui {
namespace kiwi_ui {

// Whether a frontend can take requests now.
bool Available();

// Sends a request; returns its id, or 0 if the frontend can't take it.
uint64_t Request(const std::string& json);

// The answer to a request, once the frontend gave it (only returned once).
std::optional<std::string> TakeResponse(uint64_t id);

// Fire-and-forget notification ("toast"). False if there's no frontend.
bool Toast(std::string_view kind, std::string_view title,
           std::string_view text);

// Shows the crash report. False if there's no frontend.
bool ShowCrash(std::string_view title, std::string_view text);

// A small JSON object writer for requests.
class JsonWriter {
 public:
  JsonWriter() : writer_(buffer_) { writer_.StartObject(); }

  JsonWriter& Str(const char* key, std::string_view value) {
    writer_.Key(key);
    writer_.String(value.data(), rapidjson::SizeType(value.size()));
    return *this;
  }
  JsonWriter& Int(const char* key, int64_t value) {
    writer_.Key(key);
    writer_.Int64(value);
    return *this;
  }
  JsonWriter& Bool(const char* key, bool value) {
    writer_.Key(key);
    writer_.Bool(value);
    return *this;
  }
  JsonWriter& BeginArray(const char* key) {
    writer_.Key(key);
    writer_.StartArray();
    return *this;
  }
  JsonWriter& ArrayStr(std::string_view value) {
    writer_.String(value.data(), rapidjson::SizeType(value.size()));
    return *this;
  }
  JsonWriter& BeginObject() {
    writer_.StartObject();
    return *this;
  }
  JsonWriter& EndObject() {
    writer_.EndObject();
    return *this;
  }
  JsonWriter& EndArray() {
    writer_.EndArray();
    return *this;
  }
  std::string Finish() {
    writer_.EndObject();
    return std::string(buffer_.GetString(), buffer_.GetSize());
  }

 private:
  rapidjson::StringBuffer buffer_;
  rapidjson::Writer<rapidjson::StringBuffer> writer_;
};

// Parsed answer with forgiving accessors.
class JsonReader {
 public:
  explicit JsonReader(const std::string& json) { doc_.Parse(json.c_str()); }

  bool Has(const char* key) const {
    return doc_.IsObject() && doc_.HasMember(key);
  }
  bool GetBool(const char* key, bool fallback = false) const {
    return Has(key) && doc_[key].IsBool() ? doc_[key].GetBool() : fallback;
  }
  int64_t GetInt(const char* key, int64_t fallback = 0) const {
    return Has(key) && doc_[key].IsInt64() ? doc_[key].GetInt64() : fallback;
  }
  std::string GetStr(const char* key, std::string fallback = {}) const {
    return Has(key) && doc_[key].IsString()
               ? std::string(doc_[key].GetString(),
                             doc_[key].GetStringLength())
               : fallback;
  }
  const rapidjson::Value* Get(const char* key) const {
    return Has(key) ? &doc_[key] : nullptr;
  }

 private:
  rapidjson::Document doc_;
};

// Drives one remote dialog from its OnDraw. Returns true while the frontend
// owns the dialog (the caller draws nothing then); calls on_response once,
// with the answer, after which the caller closes the dialog.
class RemoteDialog {
 public:
  bool Pump(const std::function<std::string()>& make_request,
            const std::function<void(const JsonReader&)>& on_response);
  // Sends a follow-up request for the same dialog (e.g. after creating a
  // profile the list changed).
  void Resend() { id_ = 0; }

 private:
  uint64_t id_ = 0;
  bool refused_ = false;
};

}  // namespace kiwi_ui
}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_KIWI_UI_BRIDGE_H_

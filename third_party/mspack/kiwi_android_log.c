// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: MIT

// Kiwi360: on Android libmspack is a shared library of its own (it is LGPL),
// so its debug output can't go through Xenia's logger like logging.cc does.
// It stays MIT, unlike the rest of Kiwi360: it is part of that LGPL library.

#include <android/log.h>
#include <stdarg.h>

void xenia_log(const char* fmt, ...) {
  va_list args;
  va_start(args, fmt);
  __android_log_vprint(ANDROID_LOG_WARN, "mspack", fmt, args);
  va_end(args);
}

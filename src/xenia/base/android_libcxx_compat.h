// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Force-included on Android builds only. The NDK r28 libc++ is an LLVM 19
// snapshot without std::atomic_ref, and keeps std::jthread / std::stop_token
// behind _LIBCPP_ENABLE_EXPERIMENTAL (set on the command line, since it must be
// defined before <__config> is first read). This fills in the subset of
// std::atomic_ref that the codebase uses, on top of the GCC/Clang __atomic
// builtins. Remove once the NDK's libc++ ships it.

#ifndef XENIA_BASE_ANDROID_LIBCXX_COMPAT_H_
#define XENIA_BASE_ANDROID_LIBCXX_COMPAT_H_

#if defined(__ANDROID__) && defined(__cplusplus)

#include <atomic>
#include <type_traits>

#if !defined(__cpp_lib_atomic_ref)

namespace std {

template <typename T>
class atomic_ref {
  static_assert(std::is_trivially_copyable_v<T>,
                "atomic_ref requires a trivially copyable type");

 public:
  using value_type = T;
  static constexpr bool is_always_lock_free =
      __atomic_always_lock_free(sizeof(T), 0);
  static constexpr size_t required_alignment = alignof(T) > sizeof(T)
                                                   ? alignof(T)
                                                   : sizeof(T);

  explicit atomic_ref(T& obj) noexcept : ptr_(&obj) {}
  atomic_ref(const atomic_ref&) noexcept = default;
  atomic_ref& operator=(const atomic_ref&) = delete;

  T load(memory_order order = memory_order_seq_cst) const noexcept {
    T value;
    __atomic_load(ptr_, &value, ToBuiltin(order));
    return value;
  }
  void store(T desired,
             memory_order order = memory_order_seq_cst) const noexcept {
    __atomic_store(ptr_, &desired, ToBuiltin(order));
  }
  operator T() const noexcept { return load(); }
  T operator=(T desired) const noexcept {
    store(desired);
    return desired;
  }
  T exchange(T desired,
             memory_order order = memory_order_seq_cst) const noexcept {
    T previous;
    __atomic_exchange(ptr_, &desired, &previous, ToBuiltin(order));
    return previous;
  }
  bool compare_exchange_strong(
      T& expected, T desired,
      memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_compare_exchange(ptr_, &expected, &desired, false,
                                     ToBuiltin(order), FailureOrder(order));
  }
  bool compare_exchange_weak(
      T& expected, T desired,
      memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_compare_exchange(ptr_, &expected, &desired, true,
                                     ToBuiltin(order), FailureOrder(order));
  }

  template <typename U = T, typename = enable_if_t<is_integral_v<U>>>
  T fetch_add(T arg, memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_fetch_add(ptr_, arg, ToBuiltin(order));
  }
  template <typename U = T, typename = enable_if_t<is_integral_v<U>>>
  T fetch_sub(T arg, memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_fetch_sub(ptr_, arg, ToBuiltin(order));
  }
  template <typename U = T, typename = enable_if_t<is_integral_v<U>>>
  T fetch_and(T arg, memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_fetch_and(ptr_, arg, ToBuiltin(order));
  }
  template <typename U = T, typename = enable_if_t<is_integral_v<U>>>
  T fetch_or(T arg, memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_fetch_or(ptr_, arg, ToBuiltin(order));
  }
  template <typename U = T, typename = enable_if_t<is_integral_v<U>>>
  T fetch_xor(T arg, memory_order order = memory_order_seq_cst) const noexcept {
    return __atomic_fetch_xor(ptr_, arg, ToBuiltin(order));
  }

 private:
  static constexpr int ToBuiltin(memory_order order) noexcept {
    switch (order) {
      case memory_order_relaxed:
        return __ATOMIC_RELAXED;
      case memory_order_consume:
        return __ATOMIC_CONSUME;
      case memory_order_acquire:
        return __ATOMIC_ACQUIRE;
      case memory_order_release:
        return __ATOMIC_RELEASE;
      case memory_order_acq_rel:
        return __ATOMIC_ACQ_REL;
      default:
        return __ATOMIC_SEQ_CST;
    }
  }
  // A failed compare-exchange can't release.
  static constexpr int FailureOrder(memory_order order) noexcept {
    switch (order) {
      case memory_order_release:
        return __ATOMIC_RELAXED;
      case memory_order_acq_rel:
        return __ATOMIC_ACQUIRE;
      default:
        return ToBuiltin(order);
    }
  }

  T* ptr_;
};

}  // namespace std

#endif  // !__cpp_lib_atomic_ref
#endif  // __ANDROID__ && __cplusplus
#endif  // XENIA_BASE_ANDROID_LIBCXX_COMPAT_H_

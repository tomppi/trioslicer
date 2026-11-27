#pragma once
// Android ROS macro shim for the SuperVINS estimator port.
#include <cassert>
#include <cstdio>
#ifdef __ANDROID__
#include <android/log.h>
#define ROS_INFO(...) __android_log_print(ANDROID_LOG_INFO, "SuperVINS", __VA_ARGS__)
#define ROS_DEBUG(...) do {} while (0)
#define ROS_WARN(...) __android_log_print(ANDROID_LOG_WARN, "SuperVINS", __VA_ARGS__)
#define ROS_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, "SuperVINS", __VA_ARGS__)
#define ROS_FATAL(...) __android_log_print(ANDROID_LOG_FATAL, "SuperVINS", __VA_ARGS__)
#else
#define ROS_INFO(...) do {} while (0)
#define ROS_DEBUG(...) do {} while (0)
#define ROS_WARN(...) do {} while (0)
#define ROS_ERROR(...) do {} while (0)
#define ROS_FATAL(...) do {} while (0)
#endif
#define ROS_INFO_ONCE(...) do {} while (0)
#define ROS_INFO_STREAM(...) do {} while (0)
#define ROS_DEBUG_STREAM(...) do {} while (0)
#define ROS_WARN_STREAM(...) do {} while (0)
#define ROS_ERROR_STREAM(...) do {} while (0)
#define ROS_ASSERT(x) do { (void)sizeof(x); } while (0)
#define ROS_ASSERT_MSG(...) do {} while (0)
#define ROS_BREAK() do {} while (0)

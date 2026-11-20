#include <dlfcn.h>
extern "C" void* android_load_sphal_library(const char* filename, int flag) { return dlopen(filename, flag); }
extern "C" int android_unload_sphal_library(void* handle) { return dlclose(handle); }

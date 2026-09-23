// blender_exec.cpp -- JNI wrapper: embeds Blender (background mode) with the
// slim MCP addon, for enderslicercura. Mirrors the libcuraengine_exec pattern:
// one shared lib, JNI surface, engine lifecycle managed by the app.
//
// Modes: Blender is started with `-b --python <scripts>/startup/start_blender_mcp.py`
// so the MCP socket server (addon) runs inside the engine; commands arrive via
// MCP (external AI session), no GL/viewport needed for generation.
#include <jni.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <thread>
#include <atomic>

// NOTE: the epai patched creator defines these as plain C++ (not extern "C"),
// so they must be declared here with C++ linkage to match the mangled symbols.
// (Do not include creator/creator.h -- it declares them C-style + different C++,
// which conflicts.)
void *mainBlenderInitial(int argc, const char **argv);
void mainBlenderInitial_reinit(void *pContext);
int mainBlenderLoop(void *pContext);
extern char strHomePath[256];
extern char strConfigPath[256];

static std::atomic<bool> g_running(false);
static std::atomic<bool> g_stop_requested(false);

static const char *kStopRequestEnv = "BLENDER_MCP_STOP";

static void set_env(const char *k, const std::string &v) {
    setenv(k, v.c_str(), 1);
}

static void blender_thread(std::string home, std::string config,
                           std::string scripts, std::string python,
                           std::string datafiles, std::string host, int port) {
    g_stop_requested = false;
    g_running = true;
    // Engine paths (same layout as the OBlender integration):
    //   config/  -> python, scripts, <ver>/config/datafiles
    set_env("XDG_CACHE_HOME", home);
    set_env("HOME", home);
    set_env("BLENDER_SYSTEM_DATAFILES", datafiles);
    set_env("BLENDER_SYSTEM_SCRIPTS", scripts);
    set_env("PYTHONPATH", python);
    set_env("PYTHONHOME", python);
    set_env("BLENDER_MCP_PORT", std::to_string(port));
    set_env("BLENDER_MCP_HOST", host.empty() ? "localhost" : host);
    set_env(kStopRequestEnv, "0");
    strcpy(strHomePath, home.c_str());
    strcpy(strConfigPath, config.c_str());

    std::string start_script = scripts + "/startup/start_blender_mcp.py";
    // --disable-crash-handler: Blender's own handler writes a crash file whose
    // backtrace is always empty on this port (its unwinder does not work under
    // Android) and then exits, so Android's debuggerd never sees the signal and
    // no tombstone is produced either. Leaving the fault to the platform is the
    // only way to find out where a crash actually happened.
    const char *argv[] = {
        "blender",
        "-b",
        "--disable-crash-handler",
        "--python", start_script.c_str(),
    };
    void *ctx = mainBlenderInitial(5, argv);
    // mainBlenderInitial returns when the python server loop ends (shutdown).
    (void)ctx;
    g_running = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tomppi_enderslicer_nativebridge_BlenderBridge_nativeBlenderStart(
        JNIEnv *env, jclass,
        jstring home, jstring config, jstring scripts,
        jstring python, jstring datafiles, jstring host, jint port) {
    if (g_running) return false;
    auto j2s = [](JNIEnv *env, jstring s) -> std::string {
        if (!s) return "";
        const char *c = env->GetStringUTFChars(s, nullptr);
        std::string r(c ? c : "");
        env->ReleaseStringUTFChars(s, c);
        return r;
    };
    std::string h = j2s(env, home);
    std::string c = j2s(env, config);
    std::string sc = j2s(env, scripts);
    std::string py = j2s(env, python);
    std::string df = j2s(env, datafiles);
    // Bind host: default localhost (adb forward flow). The app may pass the
    // Tailscale/CGNAT interface IP to expose the socket over the tailnet.
    std::string host_addr = j2s(env, host);
    if (host_addr.empty()) host_addr = "localhost";
    std::thread(blender_thread, h, c, sc, py, df, host_addr, (int)port).detach();
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tomppi_enderslicer_nativebridge_BlenderBridge_nativeBlenderIsRunning(
        JNIEnv *, jclass) {
    return g_running;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tomppi_enderslicer_nativebridge_BlenderBridge_nativeBlenderStop(
        JNIEnv *, jclass) {
    g_stop_requested = true;
    // The addon watches BLENDER_MCP_STOP env + port file; the simplest cross-
    // process signal here: create the stop file next to the engine home.
    // (The Kotlin side can also call the socket 'shutdown' command directly.)
}

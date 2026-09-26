// The pty that klippy talks to the printer through.
//
// klippy opens a device node and applies termios to it, so whatever sits behind it
// has to be a pty: a fifo fails tcgetattr with ENOTTY, and this phone has no
// /dev/ttyUSB for its CH340 either, because its kernel is built without
// CONFIG_USB_SERIAL. The app keeps the master end and moves bytes between it and
// the USB serial port; klippy is handed the slave path and cannot tell.
//
// Android exposes no pty API. android.system.Os has open and readlink but no ioctl,
// and ioctlInt is a hidden API that an app targeting 29 or later may not call - the
// reason every terminal emulator on the platform ships a small native library for
// exactly this. This is that library.
//
// Two calls matter. posix_openpt hands back a master descriptor; ptsname names the
// slave that goes with it. grantpt and unlockpt are what make the slave openable at
// all - without unlockpt an open of it fails with EIO.

#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <termios.h>
#include <unistd.h>

/*
 * Returns the slave path, or null. The master descriptor is written into fds[0],
 * and the caller owns it from there.
 */
JNIEXPORT jstring JNICALL
Java_com_tomppi_enderslicer_printer_KlipperPty_nativeOpenPty(JNIEnv *env, jclass clazz,
                                                             jintArray fds)
{
    if (fds == NULL || (*env)->GetArrayLength(env, fds) < 1)
        return NULL;

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0)
        return NULL;

    // Not inherited by klippy: it is started as a child process, and a second copy
    // of the master end would keep the pty alive after this side closes it.
    fcntl(master, F_SETFD, FD_CLOEXEC);

    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return NULL;
    }

    // Raw, as any serial line carrying a binary protocol has to be. The default
    // line discipline echoes back what is written to the master, translates CR and
    // LF, and in canonical mode buffers until a newline - which is how a byte the
    // bridge has just sent comes back as if the board had sent it. klippy puts the
    // slave into raw mode itself once it opens it, but the bridge is already moving
    // bytes before that happens.
    struct termios tio;
    if (tcgetattr(master, &tio) == 0) {
        cfmakeraw(&tio);
        tcsetattr(master, TCSANOW, &tio);
    }

    const char *name = ptsname(master);
    if (name == NULL) {
        close(master);
        return NULL;
    }

    jint value = master;
    (*env)->SetIntArrayRegion(env, fds, 0, 1, &value);
    return (*env)->NewStringUTF(env, name);
}

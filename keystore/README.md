# keystore

`release.jks` is the **private** key the published APK is signed with. It is not
in this repository, and neither is `keystore.properties`, which carries its
passwords. Both are gitignored.

Locally, signing reads `keystore.properties`:

    storeFile=keystore/release.jks
    storePassword=<password>
    keyAlias=trioslicer
    keyPassword=<password>

CI reads the same four values from repository secrets - `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` - and decodes the keystore to
`keystore/release.jks` before the build. A release build without a usable key
stops rather than producing `app-release-unsigned.apk`. The debug build type uses
the SDK's own throwaway `~/.android/debug.keystore`: a debug APK is a local
artifact, so its identity only has to outlive the machine that built it.

**Back the key up, with its passwords, off this machine.** Losing it means no
future build can install over the ones it signed - Android refuses an update
signed by a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and the only way
past that uninstalls the app, taking its data with it.

Certificate SHA-256:
`E4:D8:8A:C9:27:EC:B9:45:E2:56:E7:83:AE:43:12:54:78:5F:D1:10:FA:9C:78:55:9F:89:D8:32:12:8E:A5:D7`

Verify a downloaded APK against it (`apksigner` is in the Android SDK's
build-tools; the APK carries a v2 signature, so `keytool -printcert -jarfile`
cannot read it):

    apksigner verify --print-certs app-release.apk

## The key this replaced

Everything up to and including 1.3.5 was signed with a debug key committed here,
so its certificate is **public**: anyone who cloned the repository has it and can
sign an APK Android installs as an update over 1.3.5, keeping its data. That is
what made it worth replacing rather than keeping. An install signed with it has to
be uninstalled once before a `release.jks` build will install, which is why the
version that first ships the new key says so in its release notes.

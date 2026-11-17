# EasyScan workspace (bundled Easy Scanner)

This module is the complete [Easy Scanner](https://github.com/RawMechatronicsOrg/EasyScanner)
Android app, bundled into TrioSlicer so its full workspace is available from the
**EasyScan** menu. Its own README, hardware notes, printable-ChArUco-board
instructions and build documentation are upstream, at that repository.

- Source: RawMechatronicsOrg/EasyScanner, commit
  90545c2275c98dc70d99453f9561661e63c5761e (app version 0.3.1)
- License: Apache License 2.0 - see LICENSE.txt and ../THIRD_PARTY_NOTICES.md
- Rust core: the app's android/core built for arm64-v8a, shipped as
  lib/arm64-v8a/libeasyscanner_core_raw.so

Integration changes (Apache-2.0 section 4(b)): the sources are otherwise
unmodified; the Android launcher intent filters were removed (the workspace is
started only from TrioSlicer's EasyScan menu), the activities are not exported,
the Easy Scanner theme is applied to the two activities that used to inherit
the application theme, and the native library was renamed so it cannot collide
with TrioSlicer's own libeasyscanner_core.so. No scanning, calibration or UI
logic was changed.

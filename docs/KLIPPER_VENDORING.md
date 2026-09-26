# What Klipper code ships, and what publishing would mean

The app runs a Klipper host: klippy, its Python tree, and the C helper it generates
steps with. Klipper is GPLv3. This says where that code lives, what was changed, where
the boundary is, and what the options are. The decision is the user's; this is what it
needs.

## Where it is

**Not in this repository.** `app/src/main/assets/klipper/` is ignored, and
`scripts/stage-klipper-android.sh` fills it from a checkout of the stable tag
(`v0.13.0`), the same way the slicer engines are fetched rather than committed. What
this repository holds is the script that fetches it and the patches it applies.

At run time the payload is extracted into the app's private storage and executed out of
the native library directory - the only place an app targeting API 29+ may execute from.
The app's own code stays separate in the ways that matter: separate source, separate
build, separate directory, and a process boundary that is a unix socket and a file
descriptor.

## What was changed

Three patches, each applied and verified by the staging script, each with a comment
saying why:

| file | change |
| --- | --- |
| `klippy/util.py` | `create_pty` chmods the slave it opens; SELinux refuses that on Android and the node is already owned by the app |
| `klippy/mcu.py` | the pty is added as a third transport beside UART and rpmsg, so klippy uses `connect_pipe` instead of pyserial's exclusive open, which needs `flock` permission an app does not have |
| `klippy/extras/statistics.py` | `os.getloadavg` does not exist in bionic |

Nothing else is modified: the tree is otherwise the upstream tag.

## What ships beside it

Klipper's `COPYING` is staged next to the code it covers. Shipping the terms with the
thing they cover is the one obligation that applies however the rest is decided.

## Publishing, which is the open question

Today there is no distribution: the payload is built locally from upstream and installed
on the owner's own phone. Publishing the app - a release, an APK handed to someone, a
store listing - distributes Klipper's code with it, and the GPLv3's conditions come
with that. Three ways to go:

1. **Publish without the payload.** The payload is an asset and nothing else in the app
   depends on it: without it the app still slices and still prints through OctoPrint,
   and the built-in host is simply not offered. This is the smallest change and keeps
   the rest of the app's licensing untouched.
2. **Publish the app under GPLv3.** Simplest to reason about and probably not what is
   wanted for the rest of the codebase.
3. **Ask first.** The Klipper project has an interest in how its host is redistributed
   and is the right place to settle whether an app that embeds it and talks to it over
   a socket counts as a combined work. If in doubt, this is the option that costs the
   least later.

Whichever way it goes, the corresponding source for what ships is Klipper `v0.13.0`
plus the three patches above, and those patches live in this repository's staging
script, so a source offer has somewhere to point.

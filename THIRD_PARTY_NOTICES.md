# Third-party software

mRemoteDroid includes the following third-party components. Full license texts are in
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/) and are also shown in the
app under **Menu → About → Open-source licenses**.

| Component | Where | Copyright | License |
|---|---|---|---|
| [FreeRDP](https://github.com/FreeRDP/FreeRDP) 3.11 (libfreerdp3, libwinpr3, libfreerdp-client3) | `freeRDPCore/src/main/jniLibs` (built by `.github/workflows/build-freerdp.yml`) | FreeRDP contributors | Apache License 2.0 |
| FreeRDP Android client (aFreeRDP): Java code, JNI glue, resources | `freeRDPCore/src/main/java`, `freeRDPCore/src/main/cpp`, `freeRDPCore/src/main/res` | 2013 Thincast Technologies GmbH and FreeRDP contributors | Mozilla Public License 2.0 (a few JNI C files: Apache License 2.0, see each file header) |
| [OpenSSL](https://www.openssl.org/) 3.4 (libssl, libcrypto) | `freeRDPCore/src/main/jniLibs` | 1998-2024 The OpenSSL Project Authors | Apache License 2.0 |
| [cJSON](https://github.com/DaveGamble/cJSON) | `freeRDPCore/src/main/jniLibs` | 2009-2017 Dave Gamble and cJSON contributors | MIT License |
| Android Jetpack: AndroidX, Jetpack Compose, Room, Navigation, Biometric, AppCompat, Material 3, Material Icons | Gradle dependencies | The Android Open Source Project | Apache License 2.0 |
| Kotlin standard library, kotlinx.coroutines | Gradle dependencies | JetBrains s.r.o. and Kotlin contributors | Apache License 2.0 |

## Modified MPL-2.0 files

The FreeRDP Android client files under `freeRDPCore/` were modified for mRemoteDroid
(session window, input, keyboard, multiple sessions, keep-alive service and more). As
required by the Mozilla Public License 2.0, they keep their original license headers and
their source code is available in this repository. New files added to `freeRDPCore/`
by this project are marked as such in their headers or commit history.

## mRemoteNG

mRemoteDroid is an independent project. It is **not affiliated with or endorsed by** the
[mRemoteNG](https://github.com/mRemoteNG/mRemoteNG) project, and it contains **no code
from mRemoteNG** (mRemoteNG is licensed under GPL-2.0). The import and export features
read and write the `confCons.xml` file format used by mRemoteNG solely for
interoperability; the implementation in `app/src/main/java/com/mremotedroid/app/data/importer/`
was written independently. "mRemoteNG" is mentioned only to describe this compatibility.

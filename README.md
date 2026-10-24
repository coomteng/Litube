Litube
============

Litube is an Android video client with an embedded browser, native playback and built-in downloads.

Requires Android 6.0 (API 23) or later and an up-to-date Android System WebView. Picture-in-Picture is available on Android 8.0 or later when the device supports it.

## Releases

**Current version: 3.0.3**

Download installation packages and read release notes on the [releases page](https://github.com/HydeYYHH/Litube/releases).

## Features

* Ad-free playback and configurable sponsor segment skipping.
* Native controls for quality, playback speed, subtitles and audio tracks.
* Audio-only playback, background playback, picture-in-picture and a mini-player.
* Local queues, playlist playback and autoplay.
* Video, audio and playlist downloads with quality and language selection.
* Voice search.
* Live streams and live chat.
* Casting through an open-source sender.
* Settings backup and restore.
* Diagnostic reports that can be saved or shared.

## Roadmap

* [ ] Replace the WebView-based architecture with native Android components (planned for v4)

## Screenshots

<img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="" width="200"><img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" alt="" width="200"><img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="" width="200">


## Contributing

Report issues with the app version, device model, Android version and steps to reproduce. Include a diagnostic archive when available. Contributions and pull requests are welcome.

## LitubeX — this fork

LitubeX is upstream Litube plus performance work (Shorts pre-warm, faster HLS start-up),
shipped as a **separate app** (`com.coomteng.litube`) so it installs alongside Litube.
Versions are `<upstream>.<fork revision>`, e.g. 3.0.3.1. Pre-built release APKs for every
ABI are attached to each [release](https://github.com/coomteng/Litube/releases):

| file | what |
|---|---|
| `litubex-<ver>-universal-release.apk` | all ABIs, R8-minified, release-signed — pick this if unsure |
| `litubex-<ver>-arm64-v8a-release.apk` | 64-bit ARM (most phones since ~2017), smallest |
| `litubex-<ver>-armeabi-v7a-release.apk` | 32-bit ARM |
| `litubex-<ver>-x86_64-release.apk`, `-x86-` | emulators / Chromebooks |

They are built by `.github/workflows/release.yml` on every `v*` tag and signed with this
fork's own key.

Local release builds: `./gradlew assembleRelease -PabiSplits=true` with
`LITUBE_KEYSTORE`, `LITUBE_KEYSTORE_PASSWORD` (and optionally `LITUBE_KEY_ALIAS`,
`LITUBE_KEY_PASSWORD`) in the environment.

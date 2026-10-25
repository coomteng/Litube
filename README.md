Litube Plus
===========

Litube Plus is an Android video client with an embedded browser, native playback and built-in
downloads. It is a fork of [Litube](https://github.com/HydeYYHH/Litube) by HydeYYHH (GPL-3.0),
tuned for speed and for a few devices upstream does not special-case, and shipped as a
**separate app** (`com.coomteng.litube`) so it installs next to upstream rather than replacing it.

Requires Android 6.0 (API 23) or later and an up-to-date Android System WebView.
Picture-in-Picture is available on Android 8.0 or later when the device supports it.

## Releases

Up to 3.0.3.10 versions were `<upstream>.<fork revision>` (upstream 3.0.3 plus N fork
revisions); from 3.1 the fork numbers its own releases. Signed release APKs for every ABI are
attached to each [release](https://github.com/coomteng/litube_plus/releases):

| file | what |
|---|---|
| `litube-plus-<ver>-universal-release.apk` | all ABIs, R8-minified, release-signed — pick this if unsure |
| `litube-plus-<ver>-arm64-v8a-release.apk` | 64-bit ARM (most phones since ~2017), smallest |
| `litube-plus-<ver>-armeabi-v7a-release.apk` | 32-bit ARM |
| `litube-plus-<ver>-x86_64-release.apk`, `-x86-` | emulators / Chromebooks |

`SHA256SUMS.txt` lists every file. They are built by `.github/workflows/release.yml` on every
`v*` tag and signed with this fork's own key.

**In-app updates.** Once a day the app asks this repository for a newer stable release and
offers to install it (Settings → Interface → "Check for updates automatically" turns this
off; "Skip this version" silences one release). About → "Check for stable updates" does the
same on demand. The app downloads the APK for the device ABI, verifies it against
`SHA256SUMS.txt` and hands it to the system installer — Android asks for the usual
"install unknown apps" permission the first time. Nothing is installed without that
confirmation. [Obtainium](https://github.com/ImranR98/Obtainium) can track the repository
URL directly as an alternative.

## What Litube Plus changes

* Shorts tab pre-warmed in the background: bottom-bar Shorts → playing in ~1 s instead of ~4 s.
* Shorts automatic quality follows the screen (1080p on a 1080p panel; upstream's WebView
  player sizes it to the CSS viewport and sits at 360p).
* Faster watch start-up: gzip'd HLS playlists, cookie reads off the media read path.
* Shorts keep playing with the screen off / in the background when background play is on.
* Exynos 9610/9611 (Galaxy A50/A51/M31, Tab S6 Lite): VP9 kept off the hardware decoder
  whose output corrupts on fast motion; decoder rebuilt on surface and resolution changes.
  Exposed as a "Prefer H.264 over VP9" toggle, on by default on those SoCs.

Everything else is upstream Litube as of the version in the first three numbers.

## Features (from upstream)

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

## Building

Debug: `./gradlew assembleDebug` (package `com.coomteng.litube.debug`, installs side by side).

Release: `./gradlew assembleRelease -PabiSplits=true` with `LITUBE_KEYSTORE`,
`LITUBE_KEYSTORE_PASSWORD` (and optionally `LITUBE_KEY_ALIAS`, `LITUBE_KEY_PASSWORD`) in the
environment. Unset, the release build is left unsigned.

Tests: `./gradlew testDebugUnitTest` and `node --test app/src/test/script/*.test.cjs`.

## Contributing

Report issues with the app version, device model, Android version and steps to reproduce.
Include a diagnostic archive when available. Fixes that are not fork-specific are better
sent upstream to [HydeYYHH/Litube](https://github.com/HydeYYHH/Litube).

## License

GPL-3.0, same as upstream. See [LICENSE](LICENSE).

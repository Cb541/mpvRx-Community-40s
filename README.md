# mpvRx Community Seek Preview (experimental fork)

An **unofficial community modification of [mpvRx](https://github.com/Riteshp2001/mpvRx) 2.7.2** focused on fast, accurate, playback-aware seek thumbnails. This repository is independent of the upstream project.

## What this build does

- A large seek-preview window, thumbnail fallback, timeline notches, and persistent session thumbnail caching.
- Files with a **known size below 20 GB**: generate thumbnails on a **24-second full-movie first pass**, then fill the 12-second gaps.
- Files with a **known size of 20 GB or more**: **sparse 40-second ahead generation within bounded forward windows**. This is intentionally *not* the later experimental full-movie 40-second fork.
- Foreground manual seeking and video playback have priority; ahead generation can pause or slow down while buffering or scrubbing.
- Supports additional mpvRx features present in the included modified source.
- **Aggressive playback caching is preconfigured on fresh installs.** No manual `mpv.conf` paste is required; existing custom configurations are preserved.

40s background generation requires the media size to be known, enough cached/accessible data, and healthy playback. Results and speed will vary by video format and source. **This does not guarantee an instant preview at every timestamp.**

## Download and install

Visit this repository's **Releases** page and download the `arm64-v8a` APK, if available. This beta build is intended for compatible modern Android devices.

On Android, open the downloaded APK, allow installation from that source if prompted, and install it. The current debug build has application ID `app.gyrolet.mpvrx.sharedtest` and appears as **mpvRx Shared Cache Test**. It installs separately from the official app. It is *not* an upstream stable-release build.

**Updates:** Android requires updates to the same application ID to be signed with the same key. The maintainer must retain and reuse the signing key for subsequent APK releases. If switching keys, an uninstall may be required and could erase app data.

## Default playback caching

On a **fresh installation**, the app supplies the bundled `MPVRX - AGGRESSIVE PLAYBACK CACHE` configuration automatically as its editable `mpv.conf`. This happens via the app's normal preference/default-config pipeline on first launch. The player writes the active config to its private config file before playback starts. Users can edit or clear it in **Settings → Advanced → Edit mpv.conf**.

The shipped settings include `cache-on-disk=yes`, a 4096 MiB forward demuxer cache limit, a 2048 MiB backward limit, long readahead values, and a 32 MiB stream buffer. **These are limits, not preallocated disk space**, but on large streams the app may consume several GiB of temporary storage. Phones with limited storage should reduce them. Cache I/O, speed, and stability vary with device and stream; more caching does not guarantee faster thumbnail generation.

**Non-destructive upgrades:** an existing (even intentionally blank) saved `mpv.conf` is not overwritten. Existing users who want the new defaults can manually paste them into their editor or reset their config. Changing the default does not silently replace a previous user-supplied configuration.

The exact bundled settings are in `app/src/main/java/app/gyrolet/mpvrx/preferences/CommunityPlaybackCacheDefaults.kt`.

## Build from source

This repository contains the complete custom source tree, not merely a binary patch. From Linux with **JDK 17+, Android SDK platform 37, NDK 27.3.13750724, CMake 3.22.1** and Gradle network dependency access:

```bash
bash BUILD-MOBILE-APK.sh
```

For direct USB installation on a connected device with developer options / USB debugging:

```bash
bash BUILD-MOBILE-APK.sh --install
```

The script targets `:app:assembleStandardDebug` for `arm64-v8a` and produces an APK in `app/build/outputs/apk/standard/debug/`.

## Security and release status

- Beta/community build, tested in a limited environment; use at your own risk.
- **Never commit signing keys** or credentials. Signing keys do not belong in public source control.
- The APK uploaded to Releases should be rebuilt from this exact Git commit, signed consistently, and tested before publication.
- The GitHub Actions workflow is for build validation and emits a debug artifact. It does not automatically publish releases or establish persistent signing identity.

## Credits and license

Based on [mpvRx](https://github.com/Riteshp2001/mpvRx), with appreciation to its original authors and contributors and the underlying mpv / FFmpeg communities. Modified code is provided under **AGPL-3.0-or-later**, consistent with the existing `LICENSE`. Other bundled libraries may have their own licenses; retain their notices. No affiliation with the upstream maintainers is implied.

See [`RELEASE-NOTES-v0.1.0-beta.md`](RELEASE-NOTES-v0.1.0-beta.md) for release details and [`PUBLISHING.md`](PUBLISHING.md) for maintainer instructions.

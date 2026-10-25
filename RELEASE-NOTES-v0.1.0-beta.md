# mpvRx Community Seek Preview v0.1.0-beta

First community preview release based on mpvRx 2.7.2.

### Highlights

- Full-movie ahead thumbnail scheduling for known-size videos **under 20 GB**: 24-second sweep, then 12-second backfill.
- Sparse **40-second ahead thumbnail generation** for known-size videos **20 GB and above**, using bounded forward windows and existing playback safeguards.
- Enhanced seek-preview presentation with wide rounded thumbnail window and cached-thumbnail fallback.
- Persistent session thumbnail caching, fast foreground preview paths, and playback-first scheduling.
- Aggressive disk-backed mpv playback caching **enabled by default on fresh installs**; existing configs are retained.

### Install

Download the `arm64-v8a` APK below and open it on a compatible Android device. This is the **Shared Cache Test** variant, installed separately from official mpvRx.

### Known limitations

- Beta testing only; large remote files can still generate slowly or fail if playback cache bytes are unavailable.
- Ahead generation pauses when playback health or user interaction requires priority.
- Supports Android arm64 only for this published APK.
- Subsequent updates require the same app signing key.
- The bundled disk-backed cache policy can use up to roughly 6 GiB of temporary storage on long videos; reduce the limits in mpv.conf if your device has little free space.
- Source code for this exact build is in the repository at this release tag.

Unofficial fork of mpvRx. AGPL-3.0-or-later; see LICENSE and upstream acknowledgements.

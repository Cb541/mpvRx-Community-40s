# mpvRx: borderless seek thumbnail + generated-thumbnail notches

Apply on top of the latest **single-window preview** and **tvOS-inspired disk cache** build:

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-Seek-White-Notches-PATCH.zip" -d .
bash BUILD-MOBILE-APK.sh --install
```

- Removes the floating thumbnail's colored/white outline. Retains rounded corners.
- Adds slim white notches on the progress track at the timestamps for *actually generated*
  session JPEG thumbnails and still-in-memory thumbnails.
- Cache positions update during background pre-generation and direct seeking, and reset
  on a media generation change.
- Marker spacing is visually capped on narrow displays so many 12-second thumbnails
  don't turn the entire timeline white.
- Extraction, network settings, existing caching and cleanup stay unchanged.

Only four Kotlin files are replaced. This version has not been fully built on Android
in the packaging environment; compile and test with your Fedora Android SDK.

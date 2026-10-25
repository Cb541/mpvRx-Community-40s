# Two-pass 24s → 12s seek-preview generation

This update changes the **speculative thumbnail generation order only**.

* In a bounded 192-second forward section, generate missing 24-second
  timestamp positions first, with up to 8 decoded images per decoder session.
* After the 24-second positions are cached or temporarily deferred due to
  a network/cache miss, fill the intervening missing 12-second positions.
* After both passes are actually cached, advance to the next 192-second
  section. Previously successful images and playback captures count as filled.
* Never relabel a 24-second image as a 12-second thumbnail. The original
  per-frame timestamp and duplicate-frame verification stay intact.
* An interrupted, failed or unaffordable position obeys the preexisting
  cache-revision retry/cooldown limits; it must not block all 12s backfill.
* The playback monitor still suspends ahead decoding while buffering,
  paused, or seeking. Network budget, 8-frame cap, and one-decoder rules
  are unchanged.
* Known 20GB+ or size-unknown remote files remain in manual-priority mode:
  no speculative ahead decoding is introduced on huge streams.
* Thumbnail JPEG disk cache, status pill and exit cache cleanup are unchanged.

Verification: `verification-tests/RUN-AHEAD-BURST-TEST.sh`

Source: `mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt`.

# Under 20 GB: full-movie 24-second coverage, then 12-second backfill

This build is based on the user-supplied **mpvRx-Stage3.8-24s-First-12s-Backfill-Source (1).zip**, not the newer large-file experimental forks.

## Scheduling change

For remote media with a verified byte size **strictly below 20,000,000,000 bytes**:

1. Traverse every candidate `00:24, 00:48, 01:12, ...` through the end of the ENTIRE video in 24-second steps. Generate missing frames in bounded cached/remote bursts, reusing the existing per-band network/playback limits.
2. Only after finishing that complete coarse pass, return to the beginning and traverse `00:12, 00:36, 01:00, ...` to fill the intermediate 12-second samples.
3. If the remote cache does not yet contain data for a position, defer it and continue the coarse pass; don't block indefinitely. Subsequent full-length sweeps retry missing positions when permitted. The first 12-second pass does **not** begin until all the coarse timestamps across the movie have been visited, not necessarily successfully decoded.
4. Exclude the exact media duration (EOF) from candidate positions. Successful and playback-captured frames are not decoded again. A playback seek pauses pre-generation but does not restart the whole-movie pass.
5. For **5 to under 20 GB**, permit multi-frame cache-only bursts using the *existing* medium-file plan, so they are not forced into one-frame-at-a-time decoding when bytes are already cached.

The original under-10GB behavior is preserved, and **10 GB to under 20 GB** now uses the same movie-wide two-pass order rather than alternating 24s/12s within each 192-second window. Known **20GB+** and size-unknown files retain their existing conservative/no-speculative-ahead policy. Size is measured in decimal gigabytes, and exactly 20,000,000,000 bytes remains in the existing large-file band.

No changes to the tiny status pill, accurate JPEG timestamps, explicit manual seek generation, preview proxy, video-byte cache, cleanup on exit, or FFmpeg/native library.

## Verification

Run `verification-tests/RUN-FULL-MOVIE-PASS-TEST.sh` for boundary, full-length scheduling, EOF, retry, and resume checks. The repository's original ahead-burst and scrub-continuation tests may also be run. Actual remote performance depends on byte-cache availability and playback admission safeguards.

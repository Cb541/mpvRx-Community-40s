# Under 20GB full-movie 24s→12s + 20GB+ sparse 40s ahead bursts

This variant keeps the verified **under-20GB** behavior from the prior build:

- files **below 20 GB**: full-movie 24-second sweep first, then 12-second backfill.

It also changes **verified 20GB and larger** media as follows:

- speculative ahead generation is enabled again,
- but it runs only in **sparse 40-second steps**,
- within a bounded forward window,
- with the existing large-file playback/quiet/cooldown safeguards still active.

Practical effect for very large remuxes:

- less dense coverage than smaller files,
- lighter load than 24-second or 12-second large-file scans,
- some useful frames can still build ahead of playback instead of relying only on manual requests.

Unknown-size media stays conservative and does **not** gain the sparse 40-second scheduler.

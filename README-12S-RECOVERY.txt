mpvRx 12-second thumbnail generation recovery patch

- The old AVDiscard NONKEY quick path failed for each background slot on the tested HEVC remux.
- On the first failed quick extraction, the worker switches to the reliable persistent full decoder for the rest of that playback session.
- No retries of the broken fast path for each 12-second interval.
- Avoids the 12-14 second pause between jobs when playback is healthy.
- One low-priority background worker; dedicated manual-seek worker; stalls pause warming.
- Logs include bufferSeconds and paceMs so we can tune the protection with observed playback health.

Apply ZIP over current mpvRx source; BUILD-MOBILE-APK.sh --install.
The native patch below is optional initially, but recommended: HEVC quick seeks use AVDISCARD_NONREF instead of AVDISCARD_NONKEY.

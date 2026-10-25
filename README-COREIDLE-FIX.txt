FOREGROUND SEEK THUMBNAIL STUCK — core-idle gate fix

Symptom in user logs:
  seeking=false pausedForCache=false coreIdle=true bufferSeconds=62
while requested uncached thumbnail never starts decoding.

Cause: previous foregroundPreviewMayRun() unconditionally returned false
whenever core-idle=true, and the 200ms retry loop did not change that.

Fix:
  - ignore sticky core-idle and background seek-recovery state FOR FOREGROUND ONLY;
  - maintain a 1.8-second delay after a real video playback seek;
  - do not start foreground decodes while actively seeking or paused-for-cache;
  - all three background warm workers KEEP stricter protection;
  - always run pending-foreground wake monitor, including metered networks;
  - interrupt obsolete foreground decoder requests when scrubbing moves;
  - log foreground-request and foreground-decode-start to prove dispatch.

Install over your latest working mpvRx source:
  unzip -oq ~/Downloads/mpvRx-Foreground-Preview-COREIDLE-UNBLOCK-PATCH.zip -d .
  bash APPLY-FOREGROUND-COREIDLE-FIX.sh
  bash BUILD-MOBILE-APK.sh --install

No native AAR rebuild. Doesn't alter the video keyframe-seeking patch.

To inspect:
  adb logcat -c
  adb logcat -v time -s SeekPreviewPerf:D MpvPlaybackNet:D '*:S'

Expect 'foreground-request' then 'foreground-decode-start', then
'persistent-native ... success=true' and 'preview source=decoder
speculative=false ... success=true'. When actual mpv still reports
seeking=true or paused-for-cache=true, foreground waits until it clears.

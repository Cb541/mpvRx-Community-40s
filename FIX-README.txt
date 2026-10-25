Corrects the tvOS disk seek patch build failure at SeekPreviewStore.kt:109.
Adds NetworkSeekFrameRetriever constructor that accepts (source, headers, platformState).
Overlays the existing project without changing video caching or seeking UI.

Install:
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq ~/Downloads/mpvRx-tvOS-Disk-Seek-Constructor-FIX.zip -d .
bash BUILD-MOBILE-APK.sh --install

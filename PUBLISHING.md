# Publishing a community beta release

Use this repository as the **source** attached to every release. Do not publish the old experimental 'fixed' 40-second full-movie ZIP unless separately tested.

## First setup on Fedora

Ensure `git`, `gh`, `adb`, Java 17+, and the Android SDK/NDK/CMake described in README are installed. Run `gh auth status` first. Never add your GitHub access token or signing keys to source control.

A convenient way to publish from the extracted project folder:

```bash
git init -b main
git add .
git commit -m 'Initial community seek-preview release source'
gh repo create Cb541/mpvRx-Community-40s --public --source=. --remote=origin --push
```

## Build the APK from this exact source

```bash
bash BUILD-MOBILE-APK.sh
APK="$(find app/build/outputs/apk/standard/debug -type f -name '*arm64-v8a*.apk' -print -quit)"
[ -n "$APK" ] || { echo 'APK not found'; exit 1; }
cp "$APK" "$HOME/Downloads/mpvRx-Community-v0.1.0-beta-arm64-v8a.apk"
sha256sum "$HOME/Downloads/mpvRx-Community-v0.1.0-beta-arm64-v8a.apk"
```

**Important:** A Gradle debug APK is debug signed. The signing key is machine-specific. Keep `~/.android/debug.keystore` private and backed up if you plan to ship updates to the same app ID, or plan a dedicated release signing scheme before publishing a long-term stable channel. Do not upload private signing keys to GitHub.

## Release from the tagged commit

```bash
git tag -a v0.1.0-beta -m 'First public seek-preview beta'
git push origin v0.1.0-beta
gh release create v0.1.0-beta   "$HOME/Downloads/mpvRx-Community-v0.1.0-beta-arm64-v8a.apk"   --title 'mpvRx Community Seek Preview v0.1.0-beta'   --notes-file RELEASE-NOTES-v0.1.0-beta.md   --prerelease
```

The Release page provides the public APK download; the tag points to the corresponding source. Test the APK on a spare device before sharing widely, and verify that updates retain their signing identity. Refer to the official [`gh repo create`](https://cli.github.com/manual/gh_repo_create) and [`gh release create`](https://cli.github.com/manual/gh_release_create) manuals for command details.

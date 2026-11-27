<p align="center">
  <img src="docs/leaflet-icon.png" alt="Leaflet icon" width="128" height="128">
</p>

<h1 align="center">Leaflet</h1>

A personal e-reader (and audiobook player) for Android that stays offline. The name is a double meaning: a leaflet is a small leaf of a plant,
and a printed leaflet.

### [Download the latest signed APK](https://github.com/DanielCohen002/leaflet/releases/latest)

Package name `com.djcohen.leaflet`. Needs Android 11 or newer (64-bit ARM).

**Offline by design.** There is no cloud sync, no analytics, and no web dictionary: lookups use a bundled offline dictionary.
The one exception is the **Check for updates** link in the library header: only when you tap it, the app asks GitHub for the
latest release and, if there is a newer one, shows its notes and opens the APK link in your browser. It never downloads or
installs anything itself. That is the only use of the `INTERNET` permission, and it can be removed (see
[Removing the update check](#removing-the-update-check)). The other permissions are for audiobook playback in the
background (a foreground service) and the playback notification.

## What it reads

| Kind | Formats |
| --- | --- |
| Books | EPUB, MOBI / AZW / AZW3 / PRC (DRM-free), FB2, TXT, HTML, Markdown |
| Page images | PDF, CBZ, CBR, CB7, CBT (RAR/7z/tar comics are unpacked once into a CBZ when imported) |

## Audiobooks

A separate Audiobooks tab plays MP3, M4A/M4B, AAC, OGG/Opus, FLAC and WAV, from files or a whole folder (the files stay where they are).

- Remembers your place in each audiobook (and its speed), so several can be in progress at once
- Skip back/forward buttons with amounts you choose, speed choices you choose, and time left shown at the current speed
- M4B chapters (chapter list, previous/next chapter), bookmarks with notes, a sleep timer (minutes or end of chapter)
- Keeps playing with the screen off, with a notification, lock-screen and headset buttons

## Features

- Paged reading with Slide, Curl or Flip page turns (or none), inverted sepia theme, chapter list and chapter skipping, volume keys turn pages
- Light, sepia and dark (inverted) colors, several fonts, text size and line spacing
- Comics and PDFs: one page, two pages side by side (landscape), or continuous scroll; right-to-left (manga) mode; zoom
- Long-press a word to select it, copy it, or look it up in the offline dictionary (WordNet 3.0)
- Reading position is remembered per book; reading settings are remembered per file format
- Library as a list or a grid of covers; books you have read come first (most recent on top), then the rest A to Z
- Optionally keep a folder (such as `Books`) in sync: chosen once with the system folder picker, so Leaflet still needs no storage permission

## Install

Get the latest `Leaflet-<version>.apk` from the
[Releases page](https://github.com/DanielCohen002/leaflet/releases) and open it on your phone (64-bit ARM,
Android 11 or newer). You may need to allow installs from your browser or file manager. Google Play Protect can
show an "App scan recommended" prompt because the app isn't from the Play Store: choose **Install without
scanning** (or **Scan app**). New versions install over the old one and keep your library.

## Build

Requirements: JDK 17, the Android SDK (platform 36, build-tools 36), and Python 3 for the dictionary.

```
# 1. Build the offline dictionary (not stored in git; ~23 MB). Download the full WordNet 3.0 tarball from
#    https://wordnet.princeton.edu/download/current-version, extract it, then:
python tools/build_dictionary.py path/to/WordNet-3.0/dict
cp path/to/WordNet-3.0/LICENSE app/src/main/assets/WORDNET-LICENSE.txt   # already committed; shown for reference

# 2. Debug build
./gradlew assembleDebug
```

### Removing the update check

To make Leaflet fully offline again (no `INTERNET` permission, so Android itself blocks every connection), do these three
things. Everything is marked `UPDATE-CHECK` so you can find it with a search:

1. Delete `app/src/main/java/com/djcohen/offlinereader/UpdateChecker.kt`.
2. Delete the `INTERNET` line (and its comment) in `app/src/main/AndroidManifest.xml`.
3. Delete the "Check for updates" `TextView` in `LibraryActivity.kt` (the block marked `UPDATE-CHECK`).

Then change the "Offline by design" paragraph above back to "No network access", and the "Leaflet only goes online..." line
in the empty library message (`LibraryActivity.kt`, also marked `UPDATE-CHECK`). People then update by downloading the new
APK from the Releases page. When the GitHub account is renamed, update `LATEST_RELEASE_URL` in `UpdateChecker.kt`.

### Release signing

Create a keystore and a `signing.properties` file in the project root (it is gitignored):

```
STORE_FILE=C:/path/to/release.jks
STORE_PASSWORD=...
KEY_ALIAS=leaf
KEY_PASSWORD=...
```

Then `./gradlew assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`. Without
`signing.properties` the release build is simply unsigned.

## Notes

- Targets 64-bit ARM phones (`arm64-v8a`) and Android 11+ (`minSdk 30`).
- The only dependency is [libarchive-android](https://github.com/zhanghai/libarchive-android) (for RAR/7z/tar comics).
  Third-party licenses are in `app/src/main/assets/THIRD-PARTY.txt` and `WORDNET-LICENSE.txt`.
- No license has been chosen for Leaflet's own code yet.

## License

GPL-3.0. See [LICENSE](LICENSE). Free software: you can use, change and share it, and anything you share based on it must stay GPL-3.0 with its source available.

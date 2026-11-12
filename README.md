# Download Studio for Android — 0.1.5

A native Android media-downloader interface. Download Studio receives media URLs through Android's Share sheet and delegates extraction, downloading and processing to yt-dlp and FFmpeg running locally in Termux.

## What's included

- Native dark Android UI with Home, Downloads and Settings.
- A single Downloads screen containing waiting, active, paused, completed and failed items.
- Search downloads by title/name.
- Sort by name A–Z, name Z–A, newest download date, or oldest download date.
- Filter downloads by All, Video, or Audio.
- Actual media titles printed by yt-dlp before download and displayed instead of the generic Shared media label.
- YouTube thumbnails and explicit VIDEO/AUDIO labels in the list.
- Tapping a completed item opens its saved file using Android's ACTION_VIEW and the file's detected MIME type; Android chooses the compatible app.
- Queue/download details remain available for unfinished or failed items.
- Termux RUN_COMMAND integration; yt-dlp and FFmpeg execute on the phone.
- Device thermal guard, one concurrent download by default, two maximum, bounded monitoring and a Termux-side watchdog.
- GitHub Actions workflow that validates source safeguards and builds/uploads a debug APK artifact.

## Downloads screen

The previous Queue and Library destinations are combined into Downloads. All download records remain in the same persisted queue; this is a UI consolidation, not a destructive history migration.

Search checks the displayed title. For old entries still labelled Shared media, Download Studio attempts to derive a readable title from the saved output filename. New downloads receive the actual yt-dlp title through a `DS_TITLE` log marker.

Date sorting uses the completion timestamp for files completed in v0.1.4 and later. Existing records that predate this field fall back to their original creation time because their historic completion time was not stored.

Completed rows open through Android's default compatible app for the file MIME type. The file is resolved inside the user-selected SAF download folder and the app grants a temporary read URI permission. If the file is missing or no compatible app is installed, Download Studio displays an error.

## Authenticated Facebook and Instagram sessions

Download Studio uses an app-private Android WebView for Facebook and Instagram sign-in:

1. Open Settings → Authenticated Media Sessions.
2. Choose Connect Facebook or Connect Instagram.
3. Sign in on the platform's HTTPS page inside Download Studio.
4. Tap **Save session** to verify the signed-in state. WebView already persists its own cookies inside Download Studio's private app storage.
5. Reuse that session for later links until the platform expires it, the user disconnects it, or app data is cleared.

**Important security boundary:** account cookies are never exported to Termux, yt-dlp, shared storage, queue records, or download logs. For a Facebook/Instagram link, Download Studio opens the source in its private WebView and attempts to resolve a media-specific URL from the page's video element or media metadata. Only a URL on an approved Meta media CDN is passed to the existing Termux download engine. That URL may contain a time-limited signature for the selected media; it is not an account login cookie and does not grant general account access. Termux can download that media URL, but it does not receive the Facebook/Instagram session.

The resolver is intentionally conservative. Some pages use blob-backed, encrypted, DRM-protected, segmented, or otherwise unsupported playback streams; those cannot be resolved by this flow. Meta may restrict embedded-browser login or challenge sign-ins. Download Studio does not read the separately installed social apps' private storage and does not bypass DRM, access controls, or private-content restrictions.

Disconnecting a platform removes that platform's WebView cookies and origin storage. A session generally requires signing in once per platform, not before every download; sign in again only after expiration, revocation, or a platform security challenge.

## YouTube sharing

1. Open a YouTube video.
2. Tap Share and choose Download Studio.
3. Choose Video or Audio and tap Add download.

Android and Samsung control share-sheet suggestions. Download Studio cannot force itself to the first suggestion.

## Mobile safety protections

- The floating overlay has been removed; no overlay or Usage Access permissions are requested.
- One concurrent download by default; maximum two.
- yt-dlp fragment concurrency is limited to one.
- On Android 10+ the app listens for Android thermal status. New work is held at MODERATE; active downloads are interrupted at SEVERE or above and can resume after cooling.
- Download monitors read only the last 48 KiB of the log every two seconds, stop after 15 minutes of inactivity, and enforce a six-hour maximum.
- A sleeping Termux-side watchdog enforces the inactivity and runtime limits while the app UI is not visible.
- Settings engine checks are bounded rather than polling indefinitely.

No application can guarantee zero device heating. Media downloading and FFmpeg processing still consume CPU, battery, storage and network. OEM thermal reporting varies.

## Termux setup

Install Termux from its official F-Droid or GitHub distribution, then run:

```bash
pkg update -y && pkg upgrade -y
pkg install -y python ffmpeg coreutils procps
python -m pip install -U "yt-dlp[default]"
termux-setup-storage
```

Enable `allow-external-apps=true` in `~/.termux/termux.properties`, restart Termux and grant Download Studio the Run commands in Termux permission. In Download Studio, select a writable destination folder and check the engine.

## Build

Use the included GitHub Actions **Build Android APK** workflow. Download the `download-studio-android-debug-apk` artifact and extract `app-debug.apk`. This is a debug APK for testing/sideloading, not a signed Play Store release.

Downloads are intended only where authorized. DRM decryption, license acquisition, access-control circumvention and private YouTube cookie extraction are not implemented.
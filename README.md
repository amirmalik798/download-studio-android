# Download Studio for Android — 0.1.6

A native Android media-downloader interface. Download Studio receives media URLs through Android's Share sheet and delegates extraction, downloading and processing to yt-dlp and FFmpeg running locally in Termux.

## What's included

- Native dark Android UI with Home, Downloads and Settings.
- Quick Download includes one-tap clipboard paste and clear actions; the last Video/Audio choice is remembered.
- A single Downloads screen containing waiting, active, paused, completed and failed items.
- Search downloads by title/name.
- Sort by name A–Z, name Z–A, newest download date, or oldest download date.
- Filter downloads by All, Video, or Audio.
- Actual media titles printed by yt-dlp before download and displayed instead of the generic Shared media label.
- YouTube thumbnails in the Downloads list and a large Quick Download preview for public HTTPS pages that expose Open Graph, Twitter Card, image metadata, or a video poster.
- If public HTML metadata has no thumbnail, Quick Download asks the existing local yt-dlp engine for a no-download metadata preview (thumbnail/title/duration). Temporary preview output is stored only in the configured Download Studio state folder and removed after lookup.
- Thumbnail metadata lookup follows a small number of HTTPS redirects, limits downloaded HTML, and rejects private/local network destinations; it does not export account cookies or use the Termux session.
- Explicit VIDEO/AUDIO labels in the Downloads list.
- Tapping a completed item opens its saved file using Android's ACTION_VIEW and the file's detected MIME type; Android chooses the compatible app.
- Queue/download details remain available for unfinished or failed items.
- Failed or stopped items can be retried from their action menu; common website, restricted-link, expired-link and rate-limit errors are translated into clearer messages.
- Download rows include an action to copy the original source link.
- Termux RUN_COMMAND integration; yt-dlp and FFmpeg execute on the phone.
- Device thermal guard, one concurrent download by default, two maximum, bounded monitoring and a Termux-side watchdog.
- GitHub Actions workflow that validates source safeguards and builds/uploads a debug APK artifact.

## Downloads screen

The previous Queue and Library destinations are combined into Downloads. All download records remain in the same persisted queue; this is a UI consolidation, not a destructive history migration.

Search checks the displayed title. For old entries still labelled Shared media, Download Studio attempts to derive a readable title from the saved output filename. New downloads receive the actual yt-dlp title through a `DS_TITLE` log marker.

Date sorting uses the completion timestamp for files completed in v0.1.4 and later. Existing records that predate this field fall back to their original creation time because their historic completion time was not stored.

Completed rows open through Android's default compatible app for the file MIME type. The file is resolved inside the user-selected SAF download folder and the app grants a temporary read URI permission. If the file is missing or no compatible app is installed, Download Studio displays an error.

## Pasted-link thumbnail preview

Quick Download derives YouTube thumbnails from the video ID. For other supported sites it reads public page metadata such as `og:image`, `twitter:image`, `itemprop=image`, `image_src`, or a video poster. This works on many public pages, including sites that publish standard social preview metadata. A page that requires sign-in, blocks automated requests, builds metadata only with JavaScript, or does not publish an image may not return a preview. The preview lookup is separate from the downloader and does not pass login cookies to Termux.

## Download presets\n\nThe app intentionally keeps two simple presets: **Best available video + audio** and **Best available audio (MP3)**. It does not expose extra resolution or codec choices.\n\n## YouTube sharing

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
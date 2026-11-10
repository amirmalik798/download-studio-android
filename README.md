# Download Studio for Android — 0.1.3

A native Android media-downloader interface. Download Studio uses Kotlin + Jetpack Compose, receives URLs through Android's Share sheet, and delegates media extraction/download/processing to **yt-dlp and FFmpeg running locally in Termux**. It does not send media downloads to a remote download server.

## What's included

- Native dark Android UI with Home, Queue, Library and Settings.
- YouTube link sharing through Android's `ACTION_SEND` / `text/plain` share target.
- A share-intent dialog that lets the user choose Video or Audio after sharing a YouTube link.
- Termux `RUN_COMMAND` integration; yt-dlp and FFmpeg execute on the phone.
- Video and audio presets based on the supplied Media Downloader source project.
- YouTube thumbnails and explicit VIDEO/AUDIO labels on queue and library items.
- SAF folder selection and persistent access to a user-selected destination folder.
- Persistent download queue with progress, speed, ETA, stop/pause/resume and conservative concurrency settings.
- Device thermal guard, bounded download monitoring and bounded Settings engine checks.
- GitHub Actions workflow that validates source safeguards and builds/uploads a debug APK artifact.

## YouTube sharing

1. Open a YouTube video.
2. Tap **Share**.
3. Choose **Download Studio** from the app suggestions or full app list.
4. In Download Studio, choose **Video** or **Audio**, then tap **Add to queue**.

The app receives the shared webpage URL. It does not inspect YouTube's private player state, private app data or cookies.

### Share-sheet ordering limitation

The app registers for Android `ACTION_SEND` with `text/plain`, which matches YouTube's shared-link intent. Android and Samsung rank suggested apps using system and usage signals. A receiving app cannot force itself to be the first suggested app. Some Samsung versions may let the user long-press an app and pin it; this is a device/One UI feature, not controlled by Download Studio.

## Mobile safety and thermal protections

- The floating overlay service has been removed. Download Studio no longer requests `SYSTEM_ALERT_WINDOW`, Usage Access, or foreground-service permissions.
- One concurrent download is the default. Settings allows a maximum of two concurrent tasks.
- yt-dlp fragment concurrency is explicitly set to one.
- On Android 10+ (API 29+), the app listens for Android thermal-status changes. New jobs are held when Android reports MODERATE thermal status. At SEVERE or higher, an active download is interrupted and returned to the queue; it can resume after the device cools to LIGHT or NONE.
- Download monitors check every two seconds, read only the last 48 KiB of the job log, stop after 15 minutes without log activity, and enforce a six-hour maximum per task. A bounded Termux-side watchdog applies the same inactivity and six-hour limits while the downloader runs outside the app UI.
- When the user manually pauses a download, the monitor sleeps at a lower polling rate rather than repeatedly reading files.
- The Settings engine check waits at most 15 seconds for its result instead of polling forever.
- YouTube thumbnails are loaded from `i.ytimg.com` using the video ID parsed from shared YouTube URLs.

These safeguards reduce unnecessary work, but **no software can guarantee zero device heating**. yt-dlp and FFmpeg still use CPU, network, battery and storage while downloading or processing media. Android thermal reporting depends on device support. On Android versions before API 29, the app cannot access the public thermal-status API; the one-download default and two-download cap still apply.

## Required one-time Termux setup

1. Install Termux from its official F-Droid or GitHub distribution. Do not mix Termux and its plugin apps from different signing sources.
2. Open Termux and run:

   ```bash
   pkg update -y && pkg upgrade -y
   pkg install -y python ffmpeg coreutils procps
   python -m pip install -U "yt-dlp[default]"
   termux-setup-storage
   ```

3. Approve Termux storage access. Create a dedicated output folder:

   ```bash
   mkdir -p "$HOME/storage/downloads/Download Studio"
   ```

4. Enable external app commands in `~/.termux/termux.properties`:

   ```properties
   allow-external-apps=true
   ```

   Force-stop and reopen Termux after changing this setting.
5. In Android Settings → Apps → Download Studio → Permissions → Additional permissions, grant **Run commands in Termux environment**.
6. Open Download Studio → Settings → Download location and choose `Download/Download Studio` (or another writable folder). Android 11+ may not allow selecting the Download root itself through `ACTION_OPEN_DOCUMENT_TREE`; select a child folder.
7. Use Settings → Download engine → Check to verify yt-dlp and FFmpeg are available.

## Download method and presets from the supplied reference

The supplied Media Downloader project describes itself as a GUI frontend for command-line download engines, with yt-dlp as its default supported engine. Download Studio follows that approach: the Kotlin app coordinates jobs, while Termux runs the `yt-dlp` and `ffmpeg` command-line tools.

The app uses the reference project's default per-download options: `--newline`, `--ignore-config`, `--no-playlist`, and the title/ID output template. It adds `--continue`, `--concurrent-fragments 1`, and machine-readable progress/completion markers.

**Video preset — Best Available Audio Video:**

```text
-f bestvideo[ext=mp4][vcodec^=avc]+bestaudio[ext=m4a]/bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best
```

**Audio preset — Best Available Audio Only (MP3):**

```text
-f bestaudio --extract-audio --audio-quality 0 --audio-format mp3 --embed-thumbnail
```

FFmpeg from Termux performs separate-stream merging and audio extraction. `--no-playlist` is enabled by default. Playlist management from the desktop reference is not part of this Android build.

## Policy and limitations

- Downloads are intended only where the service and rights holder authorize them.
- DRM decryption, license acquisition and access-control circumvention are not implemented.
- The Android app does not extract or copy YouTube's private cookies.
- Termux, yt-dlp and FFmpeg are installed by the user from their official package sources; they are not bundled into the APK.
- Downloading and especially media conversion can heat a phone or consume battery. Keep concurrency at one for the most conservative setting and stop if the phone becomes unusually hot.

## Build the APK

The source ZIP does not bundle an APK. Push the project to GitHub and use the included **Build Android APK** workflow. On a successful run, download the artifact named `download-studio-android-debug-apk`; the archive contains `app-debug.apk`.

For local Windows builds, follow [`BUILD_APK_ON_WINDOWS.md`](BUILD_APK_ON_WINDOWS.md). The GitHub Actions workflow builds a debug APK suitable for testing/sideloading, not a signed Play Store release.

See [`REFERENCE_METHOD_AND_POLICY.md`](REFERENCE_METHOD_AND_POLICY.md) for source-grounded notes and attribution.
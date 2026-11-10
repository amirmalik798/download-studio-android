# Reference method and policy

This Android app follows the supplied `media-downloader-main(2).zip` project's command-line-engine design. It does not copy the reference project's Qt/C++ source code or UI.

## Source-derived behavior

The supplied README describes Media Downloader as a GUI frontend for multiple command-line download tools, with yt-dlp as its default supported tool. The README describes installed extensions, configurable format presets, batch links, playlist functionality and configurable concurrent downloads. Its disclaimer says the program is intended to be used in a way that does not violate applicable laws.

The supplied `extensions/yt-dlp.json` sets these default download options:

- `--newline`
- `--ignore-config`
- `--no-playlist`
- `-o "%(title).200s-%(id)s.%(ext)s"`

The same extension declares:

- batch-file argument: `-a`
- format option: `-f`
- browser-cookie option: `--cookies-from-browser`
- cookie-file option: `--cookies`

The source's `src/configure.cpp` defines this YouTube **Best Available Audio Video** preset:

`bestvideo[ext=mp4][vcodec^=avc]+bestaudio[ext=m4a]/bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best`

It defines this **Best Available Audio Only (MP3)** preset:

`-f bestaudio --extract-audio --audio-quality 0 --audio-format mp3 --embed-thumbnail`

The Android app invokes yt-dlp installed in Termux using those exact format presets and uses FFmpeg from Termux for merging and audio extraction. It also adds `--continue` and machine-readable progress/after-move markers for queue persistence and UI progress reporting. Default `--no-playlist` behavior is retained. Playlist management and other desktop-only GUI features are not implemented in this Android first version.

## Android-specific boundary

The desktop reference supports browser-cookie options. The Android app does not copy or extract private cookies from the YouTube Android app. The app receives the public webpage URL through Android's Share sheet. It does not inspect YouTube's private app data or bypass login, paywall, DRM, or other access controls.

The supplied README has a general legal-use disclaimer, but no special DRM implementation/policy. This Android app does not implement DRM decryption, license acquisition, or access-control circumvention. Users must use it only where downloading is authorized by the service and rights holder.

## Licensing and attribution

The supplied Media Downloader source identifies itself as GNU GPL version 2 or later. This Android app is a clean Kotlin client and does not copy source code from the reference Qt/C++ project. It reuses the documented yt-dlp option presets as configuration values and invokes separately installed Termux yt-dlp/FFmpeg executables.

Reference project: Media Downloader by Francis Banyikwa, 2021–2026, licensed GPL-2.0-or-later. The reference license file is present in the user-supplied archive. Termux, yt-dlp, FFmpeg and their package dependencies retain their own licenses and notices; they are installed by the user from Termux repositories and are not bundled in the APK.

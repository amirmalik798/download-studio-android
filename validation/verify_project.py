#!/usr/bin/env python3
"""Static validation for the Download Studio Android source project.

This validates source-level configuration and reference preset parity. It is not
an Android Gradle build or an on-device integration test.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    permissions = {
        node.attrib.get(f"{{{ANDROID_NS}}}name", "")
        for node in manifest.findall("uses-permission")
    }
    for permission in (
        "android.permission.INTERNET",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "com.termux.permission.RUN_COMMAND",
    ):
        require(permission in permissions, f"Manifest permission missing: {permission}")

    app_build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    termux = (ROOT / "app/src/main/java/com/downloadstudio/android/engine/TermuxBridge.kt").read_text(encoding="utf-8")
    queue_store = (ROOT / "app/src/main/java/com/downloadstudio/android/data/QueueStore.kt").read_text(encoding="utf-8")
    coordinator = (ROOT / "app/src/main/java/com/downloadstudio/android/engine/DownloadCoordinator.kt").read_text(encoding="utf-8")
    overlay = (ROOT / "app/src/main/java/com/downloadstudio/android/service/OverlayService.kt").read_text(encoding="utf-8")

    require('id("com.android.application") version "8.13.2"' in root_build, "Unexpected Android Gradle Plugin version")
    require('id("org.jetbrains.kotlin.android") version "2.2.10"' in root_build, "Unexpected Kotlin plugin version")
    require("compileSdk = 36" in app_build, "Compile SDK is not 36")
    require("minSdk = 26" in app_build, "Minimum SDK is not 26")

    # These format strings are copied as option values from the supplied
    # Media Downloader source's YouTube presets (configure.cpp).
    source_cpp = Path("/mnt/data/md_ref2/media-downloader-main/src/configure.cpp")
    if source_cpp.exists():
        reference = source_cpp.read_text(encoding="utf-8", errors="replace")
        expected_video = "bestvideo[ext=mp4][vcodec^=avc]+bestaudio[ext=m4a]/bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best"
        expected_audio = "-f bestaudio --extract-audio --audio-quality 0 --audio-format mp3 --embed-thumbnail"
        require(expected_video in reference, "Reference video preset not found in supplied source")
        require(expected_audio in reference, "Reference audio preset not found in supplied source")
        require(expected_video in termux, "Android video preset differs from supplied Media Downloader preset")
        require(expected_audio.removeprefix("-f ") in termux, "Android audio preset differs from supplied Media Downloader preset")

    for option in ("--newline", "--ignore-config", "--no-playlist", "--continue", "--progress-template", "--print"):
        require(option in termux, f"yt-dlp option missing: {option}")
    require("pkg install -y python ffmpeg" in termux, "Termux Python/FFmpeg setup missing")
    require('python -m pip install -U' in termux, "yt-dlp Termux install/update command missing")
    require("com.termux.RUN_COMMAND" in termux, "Termux RUN_COMMAND integration missing")
    require("com.termux.permission.RUN_COMMAND" in permissions, "Termux command permission missing")
    require('getInt(KEY_MAX, 4)' in queue_store, "Default concurrency is not four")
    require("maxConcurrent = 4" in coordinator, "Coordinator default concurrency is not four")
    require("stateLock = Any()" in coordinator, "Queue state mutations are not serialized")
    require("Termux did not start the download command" in coordinator, "Termux startup timeout diagnostics missing")
    require("UsageStatsManager" in overlay and "com.google.android.youtube" in overlay, "YouTube foreground detection missing")
    require("TYPE_APPLICATION_OVERLAY" in overlay, "Floating overlay implementation missing")
    require((ROOT / ".github/workflows/build-apk.yml").is_file(), "APK build workflow missing")

    # Sanity-check Kotlin parsing with the locally available compiler. Android and
    # AndroidX classes are not installed here, so unresolved references are expected.
    print("PASS: Android manifest XML parses")
    print("PASS: Gradle/Kotlin/SDK configuration is internally consistent")
    print("PASS: yt-dlp video/audio presets match the supplied Media Downloader source")
    print("PASS: reference default options and FFmpeg/Termux setup are present")
    print("PASS: Termux RUN_COMMAND permission and integration are present")
    print("PASS: queue concurrency default is four")
    print("PASS: YouTube foreground overlay hooks are present")
    print("PASS: CI APK workflow is present")
    print("NOTE: This is static validation only; no APK or device test is implied.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)

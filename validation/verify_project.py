#!/usr/bin/env python3
"""Static source validation for Download Studio Android; not a device test."""
from __future__ import annotations
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
    permissions = {node.attrib.get(f"{{{ANDROID_NS}}}name", "") for node in manifest.findall("uses-permission")}
    require("android.permission.INTERNET" in permissions, "Internet permission missing for thumbnail loading")
    require("com.termux.permission.RUN_COMMAND" in permissions, "Termux RUN_COMMAND permission missing")
    require("android.permission.SYSTEM_ALERT_WINDOW" not in permissions, "Overlay permission must be removed")
    require("android.permission.PACKAGE_USAGE_STATS" not in permissions, "Usage Access permission must be removed")
    require("android.permission.FOREGROUND_SERVICE" not in permissions, "Overlay foreground service permission must be removed")
    require("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" not in permissions, "Special-use foreground service permission must be removed")
    main_activity = (ROOT / "app/src/main/java/com/downloadstudio/android/MainActivity.kt").read_text(encoding="utf-8")
    app_build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    termux = (ROOT / "app/src/main/java/com/downloadstudio/android/engine/TermuxBridge.kt").read_text(encoding="utf-8")
    queue_store = (ROOT / "app/src/main/java/com/downloadstudio/android/data/QueueStore.kt").read_text(encoding="utf-8")
    item_model = (ROOT / "app/src/main/java/com/downloadstudio/android/data/DownloadItem.kt").read_text(encoding="utf-8")
    coordinator = (ROOT / "app/src/main/java/com/downloadstudio/android/engine/DownloadCoordinator.kt").read_text(encoding="utf-8")
    storage = (ROOT / "app/src/main/java/com/downloadstudio/android/util/StorageAccess.kt").read_text(encoding="utf-8")
    require('id("com.android.application") version "8.13.2"' in root_build, "Unexpected Android Gradle Plugin")
    require('id("org.jetbrains.kotlin.android") version "2.2.10"' in root_build, "Unexpected Kotlin plugin")
    require("compileSdk = 36" in app_build and "minSdk = 26" in app_build, "Unexpected SDK configuration")
    require('versionName = "0.1.6"' in app_build and "versionCode = 7" in app_build, "Version not bumped to 0.1.6")
    require("coil-compose" in app_build and "AsyncImage" in main_activity, "Coil thumbnail support missing")
    require("youtubeThumbnailUrl" in main_activity and "resolvePageMetadata" in main_activity and "og:image" in main_activity and "twitter:image" in main_activity and "VideoThumbnailPreview(url)" in main_activity and "height(190.dp)" in main_activity and "jsoup" in app_build and '"VIDEO"' in main_activity and '"AUDIO"' in main_activity, "Multi-site pasted URL thumbnail preview/type labels missing")
    require("previewMetadataScript" in termux and "--skip-download --dump-single-json" in termux and "DS_THUMBNAIL:" in termux and "DS_DONE" in termux, "Metadata-only yt-dlp preview fallback missing")
    require("resolveDownloaderPreview" in main_activity and "isPublicHttpsUrl" in main_activity and "--cookies" not in termux, "Safe public thumbnail extraction path missing")
    require("ContentPaste" in main_activity and "default_mode" in main_activity and "Added to Downloads" in main_activity, "Quick Download paste/remembered-mode feedback missing")
    require("DownloadCoordinator.retry(item.id)" in main_activity and "fun retry(id: String)" in coordinator, "Retry failed download action missing")
    require("deleteState" in main_activity and "fun deleteState" in storage, "Temporary preview state cleanup missing")
    require("private fun DownloadsScreen" in main_activity, "Unified Downloads screen missing")
    require("AppTab.DOWNLOADS" in main_activity and "AppTab.QUEUE" not in main_activity and "AppTab.LIBRARY" not in main_activity, "Old Queue/Library navigation remains")
    require("QueueScreen(" not in main_activity and "LibraryScreen(" not in main_activity, "Duplicate Queue/Library screens remain")
    for term in ("NAME_ASC", "NAME_DESC", "DATE_NEWEST", "DATE_OLDEST", "Search by name", "DownloadFilter.VIDEO", "DownloadFilter.AUDIO"):
        require(term in main_activity, f"Downloads browser feature missing: {term}")
    require("displayTitle(item)" in main_activity and "DS_TITLE:" in coordinator and "before_dl:DS_TITLE:%(title)s" in termux, "Actual media title capture/display missing")
    require('enqueue(url, mode, "Shared media")' not in main_activity, "Generic Shared media title is still assigned")
    require("completedAt" in item_model and "completedAt" in queue_store, "Download completion date persistence missing")
    require("completedAt = System.currentTimeMillis()" in coordinator, "Completion timestamp is not recorded")
    require("Intent.ACTION_VIEW" in main_activity and "findDownloadedDocument" in main_activity and "mimeTypeForFileName" in storage, "Default-app file opening missing")
    require("ShareFeatureCard" in main_activity and "YOUTUBE FLOATING CONTROLS" not in main_activity, "Share-first UI or floating removal missing")
    require("floatingEnabled" not in main_activity and "pendingMode" not in main_activity, "Legacy floating-control state remains")
    require(any(node.attrib.get(f"{{{ANDROID_NS}}}name") == "android.intent.action.SEND" for node in (node for node in manifest.iter() if node.tag == "action")), "ACTION_SEND share target missing")
    require(not (ROOT / "app/src/main/java/com/downloadstudio/android/service/OverlayService.kt").exists(), "Floating overlay source still exists")
    require('getInt(KEY_MAX, 1)' in queue_store and "coerceIn(1, 2)" in queue_store, "Queue concurrency default/range not conservative")
    require("maxConcurrent = 1" in coordinator and "value.coerceIn(1, 2)" in coordinator, "Coordinator concurrency limit missing")
    require("addThermalStatusListener" in coordinator and "THERMAL_STOP_THRESHOLD" in coordinator, "Thermal guard missing")
    thermal_retry_guard = "thermalDeferred.remove(id)" + chr(10) + "            update(id) { it.copy(status = DownloadStatus.STARTING"
    require(thermal_retry_guard in coordinator, "Thermal retry marker is not cleared before a new run")
    require("MAX_DOWNLOAD_RUNTIME_MS" in coordinator and "STALL_TIMEOUT_MS" in coordinator and "START_TIMEOUT_MS" in coordinator, "Bounded download monitoring missing")
    require("readStateTail" in coordinator and "fun readStateTail" in storage, "Tail-only log polling missing")
    require("current.status == DownloadStatus.QUEUED) current" in coordinator, "Progress parser status guard is malformed or missing")
    require("while (true)" not in coordinator and "while (true)" not in main_activity, "Unbounded polling loop remains")
    require('bestvideo+bestaudio/best' in termux and '-f bestaudio/best --extract-audio --audio-quality 0 --audio-format mp3' in termux, "Best-available video/audio selectors missing")
    require("--concurrent-fragments 1" in termux, "yt-dlp fragment concurrency limit missing")
    require("ds_watch_ticks" in termux and "-lt 360" in termux and "sleep 60" in termux, "Termux bounded watchdog missing")
    require("pkg install -y python ffmpeg coreutils procps" in termux, "Termux coreutils/procps packages missing")
    require("com.termux.RUN_COMMAND" in termux and 'python -m pip install -U' in termux, "Termux command/install integration missing")
    require(any((ROOT / ".github/workflows" / name).is_file() for name in ("main.yml", "build-apk.yml")), "GitHub Actions workflow missing")
    print("PASS: manifest permissions; overlay and Usage Access removed")
    print("PASS: one Downloads screen with search, name/date sorting and Audio/Video filters")
    print("PASS: real title capture, completion timestamp persistence and default-app opening")
    print("PASS: conservative concurrency, Android thermal guard and bounded monitoring")
    print("PASS: Termux watchdog, share target, yt-dlp metadata previews and CI workflow")
    print("PASS: clipboard paste, remembered mode, retries and temporary preview cleanup")
    print("NOTE: Static source validation only; not a physical-device test.")
    return 0
if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)

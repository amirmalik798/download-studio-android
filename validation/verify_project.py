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
    require("android.permission.FOREGROUND_SERVICE" in permissions, "Foreground service permission missing for background downloads")
    require("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions, "dataSync foreground service permission missing")
    require("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" not in permissions, "Special-use foreground service must not be used")
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
    require('versionName = "0.1.12"' in app_build and "versionCode = 13" in app_build, "Version not bumped to 0.1.12")
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
    for term in ("Search titles, websites, or links", "DownloadFilter.VIDEO", "DownloadFilter.AUDIO", "DownloadFilter.FAILED", "DownloadFilter.QUEUED", "Newest arrivals first", "createdAt }.thenByDescending"):
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
    require("thermalDeferred.remove(id)" in coordinator and
            "val runToken = UUID.randomUUID().toString().replace(\"-\", \"\")" in coordinator and
            "runToken = runToken" in coordinator and
            "status = DownloadStatus.STARTING" in coordinator,
            "Thermal retry state and per-attempt token are not initialized before launch")
    require("runToken: String = \"\"" in item_model and
            "runToken = o.optString(\"runToken\")" in queue_store and
            "put(\"runToken\", item.runToken)" in queue_store,
            "Per-attempt execution tokens are not persisted")
    require("MAX_DOWNLOAD_RUNTIME_MS" in coordinator and "STALL_TIMEOUT_MS" in coordinator and "START_TIMEOUT_MS" in coordinator, "Bounded download monitoring missing")
    require("readStateTail" in coordinator and "fun readStateTail" in storage, "Tail-only log polling missing")
    require("current.status in setOf(DownloadStatus.PAUSED, DownloadStatus.STOPPED, DownloadStatus.QUEUED)) current" in coordinator, "Progress parser status guard is malformed or missing")
    require("while (true)" not in coordinator and "while (true)" not in main_activity, "Unbounded polling loop remains")
    require('bestvideo*+bestaudio/best' in termux and 'bestaudio/best' in termux and '--audio-quality 0 --audio-format mp3' in termux, "Best-available video/audio selectors missing")
    manifest_text = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    require("com.sec.android.app.myfiles" in manifest_text and "com.alphainventor.filemanager" in manifest_text,
            "File manager package visibility missing")
    require("vnd.android.document/directory" in manifest_text and
            "org.openintents.extra.ABSOLUTE_PATH" in main_activity and
            "queryIntentActivities(directoryIntent" in main_activity and
            "getLaunchIntentForPackage(" in main_activity and
            "com.sec.android.app.myfiles" in main_activity and
            "com.alphainventor.filemanager" in main_activity and
            "com.sec.android.app.myfiles.external.ui.folderPath" in main_activity,
            "Folder chooser must expose installed Samsung/File Manager+ targets and preserve the directory handoff")
    require("Just once" in main_activity and "Always" in main_activity and
            "preferred_folder_manager_component" in main_activity and
            "FolderManagerChoiceDialog(" in main_activity and
            "RadioButton(" in main_activity and
            "containerColor = Panel" in main_activity and
            "titleContentColor = TextMain" in main_activity,
            "Folder manager chooser must be visibly selectable and match the app theme")
    require("configuredDownloadsPath" in main_activity and
            "absolutePath != configuredDownloadsPath" in main_activity and
            "DocumentsContract.EXTRA_INITIAL_URI, folder.uri" in main_activity and
            "FLAG_GRANT_READ_URI_PERMISSION" in main_activity,
            "Folder manager handoff must target the currently configured Downloads folder")
    require("Your media," not in main_activity and "Paste a link, check the preview, and download." not in main_activity,
            "Promotional homepage heading/subtitle should be removed")
    require("withContext(NonCancellable + Dispatchers.IO)" in main_activity and
            "StorageAccess.deleteState(context, statePath)" in main_activity,
            "Preview temp-file cleanup is not cancellation-safe")
    require("containerColor = Panel2" in main_activity and
            "BorderStroke(1.dp, Line)" in main_activity and
            'Text("Copy source link", color = TextMain' in main_activity,
            "Download actions menu does not use the app theme")
    require("--concurrent-fragments 1" in termux, "yt-dlp fragment concurrency limit missing")
    require("ds_watch_ticks" in termux and "-lt 360" in termux and "sleep 60" in termux, "Termux bounded watchdog missing")
    require("pkg install -y python ffmpeg coreutils procps" in termux, "Termux coreutils/procps packages missing")
    require("totalSize" in item_model and "processingStage" in item_model and
            "totalSize" in queue_store and "processingStage" in queue_store,
            "Download size and post-processing stage fields are not persisted")
    require("DS_POSTPROCESS|" in termux and "progress.postprocessor" in termux and
            "DS_POSTPROCESS|" in coordinator and "DownloadStatus.PROCESSING" in coordinator,
            "yt-dlp post-processing stage reporting is missing")
    require("progress.total_bytes" in termux and "progress.total_bytes_estimate" in termux and
            "totalSize = totalSize" in coordinator,
            "Live progress does not expose actual/estimated total size")
    require('android:allowBackup="true"' in manifest_text and
            'android:fullBackupContent="@xml/backup_rules"' in manifest_text and
            'android:dataExtractionRules="@xml/data_extraction_rules"' in manifest_text,
            "Android Auto Backup is not enabled with explicit backup rules")
    backup_rules = (ROOT / "app/src/main/res/xml/backup_rules.xml").read_text(encoding="utf-8")
    extraction_rules = (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").read_text(encoding="utf-8")
    ET.parse(ROOT / "app/src/main/res/xml/backup_rules.xml")
    ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml")
    require('domain="sharedpref" path="download_studio_queue.xml"' in backup_rules and
            'domain="sharedpref" path="download_studio_queue.xml"' in extraction_rules and
            "download_studio_store.xml" not in backup_rules and
            "download_studio_store.xml" not in extraction_rules,
            "Backup must include queue data without restoring the SAF URI settings")
    require("restoreFromExternal" in queue_store and "queue_backup.json" in queue_store and
            "DownloadStudioState" in queue_store and "fun configurationChanged()" in coordinator,
            "Local queue snapshot restoration after reselecting the Downloads folder is missing")
    require("durable: Boolean = false" in queue_store and "if (durable) editor.commit()" in queue_store,
            "Durable queue writes are missing")
    require("scanConfiguredDownloadsFolder" in storage and "reconcileCompletedFiles" in coordinator and
            "synchronizeDownloadsFolder" in coordinator,
            "Downloads-folder synchronization is missing")
    monitor_service = ROOT / "app/src/main/java/com/downloadstudio/android/engine/DownloadMonitorService.kt"
    monitor_source = monitor_service.read_text(encoding="utf-8") if monitor_service.is_file() else ""
    require("FOREGROUND_SERVICE_TYPE_DATA_SYNC" in monitor_source and "override fun onTimeout" in monitor_source,
            "Background download monitor or timeout recovery is missing")
    require('android:foregroundServiceType="dataSync"' in manifest_text,
            "dataSync foreground service type missing from manifest")
    require("com.termux.RUN_COMMAND" in termux and 'python -m pip install -U' in termux, "Termux command/install integration missing")
    require(any((ROOT / ".github/workflows" / name).is_file() for name in ("main.yml", "build-apk.yml")), "GitHub Actions workflow missing")
    require("active.putIfAbsent(item.id, monitor)" in coordinator,
            "Concurrent recovery can register duplicate download monitors")
    require("val beforeLaunch" in coordinator and "val afterLaunch" in coordinator and
            "signal(id, \"INT\")" in coordinator,
            "A removed queue row can still launch an orphan Termux download")
    require("id in active.keys || item.status" in coordinator,
            "Removing an item does not cancel a pending queue worker")
    require("actuallyRemoved.forEach" in coordinator,
            "Folder reconciliation cancels notifications for entries that were not actually removed")
    require("buildChildDocumentsUriUsingTree" in storage and "contentResolver.query" in storage and
            "DocumentsProvider omitted a child MIME type" in storage,
            "Folder scanning cannot distinguish an empty folder from a failed SAF query")
    require("prefs.edit().remove(KEY_QUEUE).commit()" in queue_store and
            "restoreFromExternal(context).orEmpty()" in queue_store and
            "progressValue.isFinite()" in queue_store,
            "Corrupt queue preference recovery and finite progress validation are missing")
    require('Regex("[A-Za-z0-9_-]{1,48}")' in termux and
            'mode == "VIDEO" || mode == "AUDIO"' in termux and
            "[!0-9]*" in termux,
            "Termux path identifiers, modes, or PID values are not validated")
    require("kill -CONT" in termux and "kill -$signal" in termux and
            "kill -INT" in termux,
            "Stopping a SIGSTOP-paused Termux process may leave it stuck")
    require("val executionKey = \"$id-$runToken\"" in coordinator and
            "DownloadStudioState/$executionKey.log" in coordinator and
            "DownloadStudioState/$executionKey.pid" in coordinator and
            "DownloadStudioState/$executionKey.exit" in coordinator,
            "Download attempt logs, PID and exit markers are not isolated by run token")
    require("fun signal(id: String, signal: String, runTokenOverride: String? = null)" in coordinator and
            "signal(id, \"INT\", runToken)" in coordinator and
            "val runTokens = _items.value.associate { it.id to it.runToken }" in coordinator,
            "Stop and clear actions are not bound to the matching download attempt")
    require("runToken = \"\"" in coordinator,
            "A retried download does not clear the previous execution token")
    require("update(id, durable = true)" in coordinator and
            "QueueStore.save(appContext, _items.value, durable = durable)" in coordinator,
            "The execution token is not durably committed before starting Termux")
    require("bulkClearing = false" + chr(10) + "        pump()" in coordinator and
            "cancelled.remove(id)" in coordinator[coordinator.index("private suspend fun monitorExisting"):coordinator.index("private fun parseProgress")],
            "Queue clear/recovery can strand new entries or stale cancellation markers")
    require("afterLaunch.status == DownloadStatus.QUEUED || id in thermalDeferred" in coordinator and
            "signal(id, \"INT\", runToken)" in coordinator,
            "Thermal changes during the Termux handoff can be overwritten")
    require('"\\$ds_n" -lt 120' in termux[termux.index("fun signalScript"):],
            "Stop signals do not wait long enough for delayed Termux process startup")

    run_job = coordinator[coordinator.index("private suspend fun runJob"):coordinator.index("private suspend fun monitorExisting")]
    recovery_monitor = coordinator[coordinator.index("private suspend fun monitorExisting"):coordinator.index("private fun parseProgress")]
    require("signal(id, \"INT\", runToken)" in run_job and "signal(id, \"STOP\", runToken)" in run_job and
            "signal(id, \"INT\", item.runToken)" in recovery_monitor,
            "Termination signals are not scoped to the exact download attempt")


    print("PASS: manifest permissions; foreground dataSync monitor declared; overlay and Usage Access removed")
    print("PASS: Downloads search, status/type/date filters and fixed newest-arrival order")
    print("PASS: real title capture, completion timestamp persistence and default-app opening")
    print("PASS: conservative concurrency, thermal guard, bounded monitoring and folder reconciliation")
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

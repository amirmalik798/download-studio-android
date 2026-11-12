package com.downloadstudio.android.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TermuxBridge {
    const val TERMUX_PACKAGE = "com.termux"
    private const val ACTION_RUN = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasRunPermission(context: Context): Boolean =
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED

    fun runShell(context: Context, script: String, label: String = "Download Studio", background: Boolean = true): Result<Unit> = runCatching {
        check(isInstalled(context)) { "Termux is not installed. Install the official Termux app first." }
        check(hasRunPermission(context)) { "Download Studio needs the 'Run commands in Termux' permission. Open Android Settings → Apps → Download Studio → Permissions → Additional permissions." }
        val intent = Intent(ACTION_RUN).apply {
            component = ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra(EXTRA_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_ARGS, arrayOf("-lc", script))
            putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_BACKGROUND, background)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_DESCRIPTION, "Requested by Download Studio")
        }
        context.startService(intent)
        Unit
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun setupScript(): String = "pkg update -y && pkg upgrade -y && pkg install -y python ffmpeg coreutils procps && python -m pip install -U \"yt-dlp[default]\" && termux-setup-storage"

    fun engineCheckScript(destination: String): String {
        val state = "${destination.trimEnd('/')}/DownloadStudioState"
        val report = "$state/engine.txt"
        return "mkdir -p ${shellQuote(state)}; " +
            "{ if command -v yt-dlp >/dev/null 2>&1; then printf 'yt-dlp: READY (%s)\\n' \"\$(yt-dlp --version 2>/dev/null)\"; else printf 'yt-dlp: MISSING\\n'; fi; " +
            "if command -v ffmpeg >/dev/null 2>&1; then printf 'ffmpeg: READY (%s)\\n' \"\$(ffmpeg -version 2>/dev/null | head -n 1)\"; else printf 'ffmpeg: MISSING\\n'; fi; } > ${shellQuote(report)}"
    }

    /**
     * Resolve public media metadata without downloading the media itself.
     * The app only reads the title, thumbnail and duration from a small result file.
     * No cookies, account sessions or private browser data are passed to Termux.
     */
    fun previewMetadataScript(requestId: String, url: String, destination: String): String {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid preview request id." }
        require(url.length <= 4096 && (url.startsWith("https://") || url.startsWith("http://"))) {
            "A valid media URL is required."
        }
        val q = ::shellQuote
        val stateDir = "${destination.trimEnd('/')}/DownloadStudioState"
        val result = "$stateDir/preview-$requestId.txt"
        val raw = "$stateDir/preview-$requestId.json"
        val temp = "$stateDir/preview-$requestId.tmp"
        val parser = "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); " +
            "clean=lambda v: str(v or '').replace('\\r',' ').replace('\\n',' ').strip(); " +
            "print('DS_TITLE:'+clean(d.get('title'))); " +
            "print('DS_THUMBNAIL:'+clean(d.get('thumbnail'))); " +
            "print('DS_DURATION:'+clean(d.get('duration')))"
        return "mkdir -p ${q(stateDir)}; " +
            "rm -f ${q(raw)} ${q(temp)}; printf 'PENDING\\n' > ${q(result)}; " +
            "if timeout 24s yt-dlp --ignore-config --no-warnings --no-playlist --skip-download --dump-single-json " +
            "--socket-timeout 8 --retries 1 --extractor-retries 1 ${q(url)} > ${q(raw)} 2>/dev/null; then " +
            "if python -c ${q(parser)} ${q(raw)} > ${q(temp)} 2>/dev/null; then " +
            "printf 'DS_DONE\\n' >> ${q(temp)}; mv ${q(temp)} ${q(result)}; " +
            "else printf 'DS_ERROR\\nDS_DONE\\n' > ${q(result)}; fi; " +
            "else printf 'DS_ERROR\\nDS_DONE\\n' > ${q(result)}; fi; " +
            "rm -f ${q(raw)} ${q(temp)}"
    }

    fun downloadScript(id: String, url: String, mode: String, destination: String): String {
        val q = ::shellQuote
        val stateDir = "$destination/DownloadStudioState"
        val log = "$stateDir/$id.log"
        val pid = "$stateDir/$id.pid"
        val exit = "$stateDir/$id.exit"
        // Keep titles short enough for Android/Linux filename limits, including Unicode titles.
        val output = "$destination/%(title).40s-%(id)s.%(ext)s"
        val common = "--newline --progress --concurrent-fragments 1 --ignore-config --no-playlist --continue -o ${q(output)} " +
            "--progress-template ${q("download:DS_PROGRESS|%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes_estimate)s")} " +
            "--print ${q("before_dl:DS_TITLE:%(title)s")} " +
            "--print ${q("before_dl:DS_THUMBNAIL:%(thumbnail)s")} " +
            "--print ${q("after_move:DS_COMPLETE:%(filepath)s")}"
        val preset = if (mode == "AUDIO") {
            "-f bestaudio/best --extract-audio --audio-quality 0 --audio-format mp3 --embed-thumbnail"
        } else {
            // Select the highest-ranked separate video and audio streams yt-dlp can provide.
            // Fall back to the best combined format when separate streams are unavailable.
            "-f ${q("bestvideo+bestaudio/best")}"
        }
        return "mkdir -p " + q(destination) + " " + q(stateDir) + "; " +
            "rm -f " + q(log) + " " + q(exit) + " " + q(pid) + "; " +
            "PYTHONUNBUFFERED=1 yt-dlp $common $preset " + q(url) + " > " + q(log) + " 2>&1 & " +
            "ds_pid=\$!; printf '%s' \"\$ds_pid\" > " + q(pid) + "; " +
            "(ds_watch_ticks=0; ds_watch_start=\$(date +%s); " +
            "while [ \"\$ds_watch_ticks\" -lt 360 ] && kill -0 \"\$ds_pid\" 2>/dev/null; do " +
            "sleep 60; ds_watch_ticks=\$((ds_watch_ticks+1)); ds_now=\$(date +%s); " +
            "ds_log_mtime=\$(stat -c %Y " + q(log) + " 2>/dev/null || echo \"\$ds_watch_start\"); " +
            "ds_state=\$(ps -o stat= -p \"\$ds_pid\" 2>/dev/null | tr -d ' '); " +
            "case \"\$ds_state\" in T*) ds_is_stopped=1 ;; *) ds_is_stopped=0 ;; esac; " +
            "if [ \"\$((ds_now-ds_watch_start))\" -ge 21600 ] || { [ \"\$ds_is_stopped\" -eq 0 ] && [ \"\$((ds_now-ds_log_mtime))\" -ge 900 ]; }; then " +
            "kill -INT \"\$ds_pid\" 2>/dev/null || true; break; fi; done) & " +
            "ds_watch_pid=\$!; wait \"\$ds_pid\"; ds_code=\$?; " +
            "kill \"\$ds_watch_pid\" 2>/dev/null || true; wait \"\$ds_watch_pid\" 2>/dev/null || true; " +
            "printf '%s' \"\$ds_code\" > " + q(exit) + "; exit \"\$ds_code\""
    }

    fun signalScript(destination: String, id: String, signal: String): String {
        require(signal in setOf("STOP", "CONT", "INT", "TERM"))
        val pidFile = "$destination/DownloadStudioState/$id.pid"
        return "ds_n=0; while [ ! -f ${shellQuote(pidFile)} ] && [ \"\$ds_n\" -lt 20 ]; do sleep 0.25; ds_n=\$((ds_n+1)); done; " +
            "if [ -f ${shellQuote(pidFile)} ]; then ds_pid=\$(cat ${shellQuote(pidFile)}); kill -$signal \"\$ds_pid\" 2>/dev/null || true; fi"
    }
}

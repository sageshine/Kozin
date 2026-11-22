package kr.kozin.ytaudio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var canceled = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                canceled = true
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url == null || job?.isActive == true) return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "음원"
                val selector = intent.getStringExtra(EXTRA_SELECTOR) ?: SELECTOR_BEST
                val thumb = intent.getBooleanExtra(EXTRA_THUMB, false)
                val fileName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                canceled = false
                ServiceCompat.startForeground(
                    this, NOTI_PROGRESS, progressNotification(title, 0, true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                DlBus.state.value = DlState.Running(title, 0, "준비 중…")
                job = scope.launch { run(url, title, selector, thumb, fileName) }
            }
        }
        return START_NOT_STICKY
    }

    private fun run(url: String, title: String, selector: String, thumb: Boolean, fileName: String) {
        val root = File(cacheDir, "dl").apply { deleteRecursively() }
        var work = File(root, "${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            Engine.ensureInit(this)
            // 내장 엔진은 앱을 만든 날짜에 멈춰 있어 유튜브가 거절한다. 처음 한 번은 먼저 최신으로 바꾼다.
            var updated = false
            if (YoutubeDL.getInstance().versionName(this).isNullOrEmpty()) {
                updated = updateEngine(title)
            }
            try {
                fetch(url, title, selector, thumb, work)
            } catch (e: Throwable) {
                if (canceled || updated || !updateEngine(title)) throw e
                work = File(root, "${System.currentTimeMillis()}").apply { mkdirs() }
                fetch(url, title, selector, thumb, work)
            }

            val raw = work.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
                ?.maxByOrNull { it.length() }
                ?: error("받은 음원 파일을 찾지 못했습니다.")

            // 직접 적은 이름이 있으면 그대로, 없으면 제목을 자동으로 다듬어 쓴다.
            val base = Names.sanitize(fileName).ifEmpty { Names.clean(raw.nameWithoutExtension) }
            val named = File(work, "$base.${raw.extension}")
            val file = if (named == raw || raw.renameTo(named)) named else raw

            DlBus.state.value = DlState.Running(title, 100, "저장 중…")
            val mime = mimeOf(file)
            val (uri, where) = save(file, mime)
            DlBus.state.value = DlState.Done(file.name, uri, mime, where)
            notifier().notify(NOTI_DONE, doneNotification(file.name, uri, mime))
        } catch (e: Throwable) {
            DlBus.state.value =
                if (canceled) DlState.Idle else DlState.Failed(readableError(e))
        } finally {
            root.deleteRecursively()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fetch(url: String, title: String, selector: String, thumb: Boolean, work: File) {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-f", selector)
            // -x 만 주고 형식을 지정하지 않으면 다시 인코딩하지 않고 음원만 꺼낸다.
            addOption("-x")
            addOption("--embed-metadata")
            if (thumb) {
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
            }
            // 유튜브 서명 풀이에 필요한 스크립트가 엔진에 없으면 받아 오게 한다.
            addOption("--remote-components", "ejs:github")
            addOption("--no-mtime")
            addOption("-o", work.absolutePath + "/%(title).60s.%(ext)s")
        }
        YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
            val percent = progress.toInt().coerceIn(0, 100)
            DlBus.state.value = DlState.Running(title, percent, line.trim())
            val now = System.currentTimeMillis()
            if (now - lastNotify > 1000) {
                lastNotify = now
                notifier().notify(NOTI_PROGRESS, progressNotification(title, percent, false))
            }
        }
    }

    /** 엔진을 새로 받았으면 true. 이미 최신이거나 받지 못했으면 false. */
    private fun updateEngine(title: String): Boolean {
        DlBus.state.value = DlState.Running(title, 0, "추출 엔진을 최신으로 바꾸는 중…")
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(this, Engine.CHANNEL) ==
                YoutubeDL.UpdateStatus.DONE
        } catch (e: Throwable) {
            false
        }
    }

    /** 경고 줄은 빼고 실제 오류 줄만 보여 준다. */
    private fun readableError(e: Throwable): String {
        val text = e.message?.trim() ?: return e.toString()
        val errors = text.lines().filter { it.trimStart().startsWith("ERROR") }
        return (if (errors.isNotEmpty()) errors.joinToString("\n") else text).takeLast(600)
    }

    /** 고른 폴더가 있으면 거기에, 없거나 쓸 수 없으면 기본 위치에 저장한다. */
    private fun save(file: File, mime: String): Pair<Uri, String> {
        val tree = Prefs.tree(this)
        if (tree != null) {
            try {
                return saveToTree(file, mime, tree) to "${Prefs.label(tree)}/${file.name}"
            } catch (e: Throwable) {
                // 폴더가 지워졌거나 권한이 사라진 경우 기본 위치로 넘어간다.
            }
        }
        val note = if (tree != null) " (고른 폴더에 쓸 수 없어 기본 위치에 저장)" else ""
        return saveToMusic(file, mime) to "Music/YTAudio/${file.name}$note"
    }

    private fun saveToTree(file: File, mime: String, tree: Uri): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val doc = DocumentsContract.createDocument(contentResolver, parent, mime, file.name)
            ?: error("고른 폴더에 파일을 만들지 못했습니다.")
        try {
            contentResolver.openOutputStream(doc)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Throwable) {
            try { DocumentsContract.deleteDocument(contentResolver, doc) } catch (ignored: Throwable) {}
            throw e
        }
        return doc
    }

    /** 휴대폰의 Music/YTAudio 폴더에 넣는다. 저장 권한 없이 쓸 수 있는 방식이다. */
    private fun saveToMusic(file: File, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/YTAudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: error("저장 위치를 만들지 못했습니다.")
        try {
            contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        val known = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (known != null && known.startsWith("audio/")) return known
        return when (ext) {
            "m4a", "aac" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    private fun notifier() = getSystemService(NotificationManager::class.java)

    private fun progressNotification(title: String, percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "준비 중…" else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "취소", cancel)
            .build()
    }

    private fun doneNotification(name: String, uri: Uri, mime: String): Notification {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val play = PendingIntent.getActivity(
            this, 2, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("저장 완료")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(play)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SELECTOR = "selector"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_NAME = "name"

        const val SELECTOR_BEST = "bestaudio/best"
        const val SELECTOR_M4A = "bestaudio[ext=m4a]/bestaudio/best"
        const val SELECTOR_OPUS = "bestaudio[acodec=opus]/bestaudio/best"

        private const val PROCESS_ID = "ytaudio"
        private const val NOTI_PROGRESS = 1
        private const val NOTI_DONE = 2
        private val AUDIO_EXT = setOf("m4a", "opus", "ogg", "webm", "mp3", "aac", "flac", "wav", "mka")

        fun start(
            context: Context, url: String, title: String, selector: String, thumb: Boolean,
            fileName: String
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SELECTOR, selector)
                .putExtra(EXTRA_THUMB, thumb)
                .putExtra(EXTRA_NAME, fileName)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}

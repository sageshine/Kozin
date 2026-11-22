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
                canceled = false
                ServiceCompat.startForeground(
                    this, NOTI_PROGRESS, progressNotification(title, 0, true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                DlBus.state.value = DlState.Running(title, 0, "준비 중…")
                job = scope.launch { run(url, title, selector, thumb) }
            }
        }
        return START_NOT_STICKY
    }

    private fun run(url: String, title: String, selector: String, thumb: Boolean) {
        val work = File(cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            Engine.ensureInit(this)
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

            val file = work.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
                ?.maxByOrNull { it.length() }
                ?: error("받은 음원 파일을 찾지 못했습니다.")

            DlBus.state.value = DlState.Running(title, 100, "저장 중…")
            val mime = mimeOf(file)
            val uri = saveToMusic(file, mime)
            DlBus.state.value = DlState.Done(file.name, uri, mime)
            notifier().notify(NOTI_DONE, doneNotification(file.name, uri, mime))
        } catch (e: Throwable) {
            DlBus.state.value =
                if (canceled) DlState.Idle
                else DlState.Failed(e.message?.trim()?.takeLast(600) ?: e.toString())
        } finally {
            work.deleteRecursively()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

        const val SELECTOR_BEST = "bestaudio/best"
        const val SELECTOR_M4A = "bestaudio[ext=m4a]/bestaudio/best"
        const val SELECTOR_OPUS = "bestaudio[acodec=opus]/bestaudio/best"

        private const val PROCESS_ID = "ytaudio"
        private const val NOTI_PROGRESS = 1
        private const val NOTI_DONE = 2
        private val AUDIO_EXT = setOf("m4a", "opus", "ogg", "webm", "mp3", "aac", "flac", "wav", "mka")

        fun start(context: Context, url: String, title: String, selector: String, thumb: Boolean) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SELECTOR, selector)
                .putExtra(EXTRA_THUMB, thumb)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}

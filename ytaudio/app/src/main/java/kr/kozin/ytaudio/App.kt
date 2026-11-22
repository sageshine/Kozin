package kr.kozin.ytaudio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL = "downloads"
    }
}

/** yt-dlp·ffmpeg 초기화. 첫 실행 때 내장 파일을 풀어서 몇 초 걸리므로 백그라운드에서만 부른다. */
object Engine {
    private var ready = false

    @Synchronized
    fun ensureInit(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }
}

sealed interface DlState {
    data object Idle : DlState
    data class Running(val title: String, val percent: Int, val line: String) : DlState
    data class Done(val name: String, val uri: Uri, val mime: String) : DlState
    data class Failed(val message: String) : DlState
}

object DlBus {
    val state = MutableStateFlow<DlState>(DlState.Idle)
}

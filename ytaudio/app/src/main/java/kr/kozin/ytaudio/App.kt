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
    /** 유튜브가 바뀌면 고친 내용이 가장 먼저 올라오는 배포 채널. */
    val CHANNEL: YoutubeDL.UpdateChannel = YoutubeDL.UpdateChannel.NIGHTLY

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
    data class Done(val name: String, val uri: Uri, val mime: String, val where: String) : DlState
    data class Failed(val message: String) : DlState
}

object DlBus {
    val state = MutableStateFlow<DlState>(DlState.Idle)
}

/** 저장 폴더 설정. 고르지 않으면 Music/YTAudio 에 저장한다. */
object Prefs {
    private const val KEY_TREE = "tree"

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun tree(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)

    fun setTree(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_TREE, uri?.toString()).apply()
    }

    /** "primary:Music/내폴더" 같은 주소에서 사람이 읽을 폴더 이름만 꺼낸다. */
    fun label(tree: Uri): String = try {
        val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val head = if (volume == "primary") "내장 저장공간" else "SD 카드"
        if (path.isEmpty()) head else "$head/$path"
    } catch (e: Throwable) {
        "고른 폴더"
    }
}

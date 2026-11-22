package kr.kozin.ytaudio

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var url: EditText
    private lateinit var fetch: Button
    private lateinit var infoCard: View
    private lateinit var thumb: ImageView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var format: RadioGroup
    private lateinit var embedThumb: MaterialSwitch
    private lateinit var download: Button
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var cancel: Button
    private lateinit var open: Button
    private lateinit var engine: TextView
    private lateinit var update: Button
    private lateinit var saveDir: TextView
    private lateinit var resetDir: Button

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            Prefs.setTree(this, uri)
            showSaveDir()
        }
    }

    /** 정보 조회로 확인한 주소와 제목. 주소가 바뀌면 제목을 다시 조회한다. */
    private var infoUrl: String? = null
    private var infoTitle: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        url = findViewById(R.id.url)
        fetch = findViewById(R.id.fetch)
        infoCard = findViewById(R.id.infoCard)
        thumb = findViewById(R.id.thumb)
        title = findViewById(R.id.title)
        sub = findViewById(R.id.sub)
        format = findViewById(R.id.format)
        embedThumb = findViewById(R.id.embedThumb)
        download = findViewById(R.id.download)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        cancel = findViewById(R.id.cancel)
        open = findViewById(R.id.open)
        engine = findViewById(R.id.engine)
        update = findViewById(R.id.update)
        saveDir = findViewById(R.id.saveDir)
        resetDir = findViewById(R.id.resetDir)

        findViewById<Button>(R.id.pickDir).setOnClickListener { pickDir.launch(Prefs.tree(this)) }
        resetDir.setOnClickListener {
            Prefs.setTree(this, null)
            showSaveDir()
        }
        showSaveDir()

        findViewById<Button>(R.id.paste).setOnClickListener { paste() }
        fetch.setOnClickListener { fetchInfo() }
        download.setOnClickListener { startDownload() }
        cancel.setOnClickListener { DownloadService.cancel(this) }
        update.setOnClickListener { updateEngine() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DlBus.state.collect { render(it) }
            }
        }

        showEngineVersion()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** 유튜브 앱의 "공유"로 넘어온 글에서 주소만 골라낸다. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val link = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        url.setText(link)
        fetchInfo()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val link = extractUrl(text)
        if (link == null) {
            status.text = "복사해 둔 주소가 없습니다."
            return
        }
        url.setText(link)
        fetchInfo()
    }

    private fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value }

    private fun currentUrl(): String? = extractUrl(url.text?.toString())

    private fun fetchInfo() {
        val link = currentUrl()
        if (link == null) {
            status.text = "유튜브 주소를 넣어 주세요."
            return
        }
        if (busy) return
        setBusy(true, "정보를 가져오는 중… (처음 실행은 조금 걸립니다)")
        lifecycleScope.launch {
            try {
                val (info, bitmap) = withContext(Dispatchers.IO) {
                    Engine.ensureInit(this@MainActivity)
                    val request = YoutubeDLRequest(link).apply { addOption("--no-playlist") }
                    val info = YoutubeDL.getInstance().getInfo(request)
                    info to loadBitmap(info.thumbnail)
                }
                infoUrl = link
                infoTitle = info.title
                title.text = info.title ?: "(제목 없음)"
                sub.text = listOfNotNull(info.uploader, formatDuration(info.duration))
                    .joinToString(" · ")
                thumb.setImageBitmap(bitmap)
                thumb.visibility = if (bitmap != null) View.VISIBLE else View.GONE
                infoCard.visibility = View.VISIBLE
                setBusy(false, "")
            } catch (e: Throwable) {
                infoCard.visibility = View.GONE
                setBusy(false, "정보를 가져오지 못했습니다.\n" + shortError(e))
            }
        }
    }

    private fun loadBitmap(link: String?): Bitmap? = try {
        link?.let { URL(it).openStream().use { stream -> BitmapFactory.decodeStream(stream) } }
    } catch (e: Throwable) {
        null
    }

    private fun startDownload() {
        val link = currentUrl()
        if (link == null) {
            status.text = "유튜브 주소를 넣어 주세요."
            return
        }
        val selector = when (format.checkedRadioButtonId) {
            R.id.fmtM4a -> DownloadService.SELECTOR_M4A
            R.id.fmtOpus -> DownloadService.SELECTOR_OPUS
            else -> DownloadService.SELECTOR_BEST
        }
        val name = infoTitle?.takeIf { infoUrl == link } ?: "음원"
        DownloadService.start(this, link, name, selector, embedThumb.isChecked)
    }

    private fun showSaveDir() {
        val tree = Prefs.tree(this)
        saveDir.text = "저장 위치: " + (tree?.let(Prefs::label) ?: "Music/YTAudio (기본)")
        resetDir.visibility = if (tree != null) View.VISIBLE else View.GONE
    }

    private fun updateEngine() {
        if (busy) return
        setBusy(true, "추출 엔진을 업데이트하는 중…")
        lifecycleScope.launch {
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    Engine.ensureInit(this@MainActivity)
                    YoutubeDL.getInstance().updateYoutubeDL(this@MainActivity, Engine.CHANNEL)
                }
                if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "이미 최신 버전입니다."
                else "업데이트했습니다."
            } catch (e: Throwable) {
                "업데이트하지 못했습니다.\n" + shortError(e)
            }
            setBusy(false, message)
            showEngineVersion()
        }
    }

    private fun showEngineVersion() {
        lifecycleScope.launch {
            val version = withContext(Dispatchers.IO) {
                try {
                    Engine.ensureInit(this@MainActivity)
                    YoutubeDL.getInstance().versionName(this@MainActivity)
                        ?: YoutubeDL.getInstance().version(this@MainActivity)
                } catch (e: Throwable) {
                    null
                }
            }
            engine.text = "추출 엔진(yt-dlp) 버전: " +
                (version?.takeIf { it.isNotEmpty() } ?: "내장 버전 (첫 다운로드 때 자동으로 최신으로 바뀝니다)")
        }
    }

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        fetch.isEnabled = !value
        update.isEnabled = !value
        status.text = message
    }

    private fun render(state: DlState) {
        val running = state is DlState.Running
        download.isEnabled = !running
        cancel.visibility = if (running) View.VISIBLE else View.GONE
        progress.visibility = if (running) View.VISIBLE else View.GONE
        open.visibility = if (state is DlState.Done) View.VISIBLE else View.GONE
        when (state) {
            is DlState.Idle -> Unit
            is DlState.Running -> {
                progress.isIndeterminate = state.percent <= 0
                if (state.percent > 0) progress.setProgressCompat(state.percent, true)
                status.text = state.line
            }
            is DlState.Done -> {
                status.text = "저장했습니다: ${state.where}"
                showEngineVersion()
                open.setOnClickListener {
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(state.uri, state.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        startActivity(view)
                    } catch (e: Throwable) {
                        status.text = "이 파일을 재생할 앱이 없습니다."
                    }
                }
            }
            is DlState.Failed -> {
                status.text = "받지 못했습니다.\n" + state.message
                showEngineVersion()
            }
        }
    }

    private fun shortError(e: Throwable): String =
        (e.message ?: e.toString()).trim().takeLast(400)

    private fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

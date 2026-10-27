package com.leog.photoframe

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var imgA: ImageView
    private lateinit var imgB: ImageView
    private lateinit var clock: TextView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var nightMask: View
    private lateinit var nightClock: TextView

    private val repo = ManifestRepository()
    private val music = MusicPlayer(this)

    private lateinit var controller: SlideshowController

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var loadedUrl: String? = null
    private var currentAlbum: Album? = null
    private var tempNightOverride = false
    private var loading = false

    private val hideHintRunnable = Runnable { hint.visibility = View.GONE }

    private val periodicRunnable = object : Runnable {
        override fun run() {
            val shouldNight = isNightNow() && !tempNightOverride
            val showingNight = nightMask.visibility == View.VISIBLE
            if (shouldNight != showingNight) {
                applyNightMode()
            } else if (showingNight) {
                updateNightClock()
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgA = findViewById(R.id.imgA)
        imgB = findViewById(R.id.imgB)
        clock = findViewById(R.id.clock)
        caption = findViewById(R.id.caption)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        nightMask = findViewById(R.id.nightMask)
        nightClock = findViewById(R.id.nightClock)

        controller = SlideshowController(
            this, imgA, imgB, clock, caption
        ) { msg -> showStatus(msg) }

        enterFullscreen()
        loadAlbum()
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        tempNightOverride = false

        if (Prefs.needReload) {
            Prefs.needReload = false
            loadAlbum()
        } else if (Prefs.manifestUrl != loadedUrl) {
            loadAlbum()
        } else if (currentAlbum != null) {
            controller.reorderKeepCurrent()
            controller.resume()
            music.setVolume(Prefs.volume / 100f)
        }

        applyNightMode()
        handler.removeCallbacks(periodicRunnable)
        handler.post(periodicRunnable)
    }

    override fun onPause() {
        super.onPause()
        music.pause()
        controller.pause()
        handler.removeCallbacks(periodicRunnable)
    }

    override fun onDestroy() {
        uiScope.cancel()
        handler.removeCallbacksAndMessages(null)
        controller.release()
        music.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 按键

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 夜间息屏状态下，任意键先唤醒
        if (nightMask.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                tempNightOverride = true
                applyNightMode()
                openSettings()
                return true
            }
            tempNightOverride = true
            applyNightMode()
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                controller.next()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                controller.prev()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (currentAlbum == null && !loading) {
                    loadAlbum()
                } else {
                    controller.togglePause()
                    flashHint(if (controller.isPaused()) "已暂停 · 按 OK 继续" else "继续播放")
                }
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                openSettings()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ 数据

    private fun loadAlbum() {
        if (loading) return
        val url = Prefs.manifestUrl
        if (url.isBlank()) {
            showStatus("还没设置相册地址\n\n按菜单键进入设置，填入清单地址")
            return
        }
        loading = true
        showStatus("正在读取相册…")

        uiScope.launch {
            try {
                val album = repo.load(url)
                if (album.photos.isEmpty()) {
                    showStatus("清单里没有照片\n请检查 N1 上的 manifest.json")
                    return@launch
                }
                currentAlbum = album
                loadedUrl = url

                controller.bind(album)
                controller.start()

                music.setPlaylist(album.music)
                music.setVolume(Prefs.volume / 100f)
                music.trimCache()

                showStatus(null)
                flashHint("共 ${album.photos.size} 张照片")
                applyNightMode()
            } catch (e: Exception) {
                showStatus("读取失败：${e.message}\n\n按 OK 重试 · 按菜单键进设置")
            } finally {
                loading = false
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------ UI

    private fun enterFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showStatus(msg: String?) {
        if (msg == null) {
            status.visibility = View.GONE
        } else {
            status.text = msg
            status.visibility = View.VISIBLE
        }
    }

    private fun flashHint(msg: String) {
        hint.text = msg
        hint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 2600L)
    }

    private fun isNightNow(): Boolean {
        if (!Prefs.nightMode) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = Prefs.nightStartMin
        val end = Prefs.nightEndMin
        return if (start <= end) {
            now >= start && now < end
        } else {
            now >= start || now < end
        }
    }

    private fun applyNightMode() {
        val night = isNightNow() && !tempNightOverride

        nightMask.visibility = if (night) View.VISIBLE else View.GONE
        nightClock.visibility = if (night) View.VISIBLE else View.GONE

        if (night) {
            clock.visibility = View.GONE
            caption.visibility = View.GONE
            status.visibility = View.GONE
            hint.visibility = View.GONE
            updateNightClock()
            controller.setNight(true)
            music.pause()
        } else {
            controller.setNight(false)
            if (Prefs.musicEnabled) music.resume() else music.pause()
        }
    }

    private fun updateNightClock() {
        nightClock.text = timeFormat.format(Date())
    }
}

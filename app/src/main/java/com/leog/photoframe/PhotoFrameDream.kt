package com.leog.photoframe

import android.service.dreams.DreamService
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统屏保。电视待机/长时间无操作时由系统拉起，
 * 复用主界面的布局和轮播逻辑，只是不带设置入口。
 */
class PhotoFrameDream : DreamService() {

    private val repo = ManifestRepository()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: SlideshowController? = null
    private var music: MusicPlayer? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        setContentView(R.layout.activity_main)

        // DreamService.findViewById 返回 View（非泛型），需要显式转换
        val imgA = findViewById(R.id.imgA) as ImageView
        val imgB = findViewById(R.id.imgB) as ImageView
        val clock = findViewById(R.id.clock) as TextView
        val caption = findViewById(R.id.caption) as TextView

        val slideshow = SlideshowController(this, imgA, imgB, clock, caption) { }
        controller = slideshow

        val player = MusicPlayer(this)
        music = player

        uiScope.launch {
            try {
                val album = repo.load(Prefs.manifestUrl)
                if (album.photos.isEmpty()) return@launch
                slideshow.bind(album)
                slideshow.start()
                player.setPlaylist(album.music)
                player.setVolume(Prefs.volume / 100f)
                player.setFade(Prefs.fadeInSec, Prefs.fadeOutSec)
                if (Prefs.musicEnabled && album.music.isNotEmpty()) player.start()
            } catch (_: Exception) {
                // 屏保拉不到清单就保持黑屏，不弹任何错误
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        controller?.resume()
        music?.resume()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        controller?.pause()
        music?.pause()
    }

    override fun onDetachedFromWindow() {
        uiScope.cancel()
        controller?.release()
        music?.release()
        controller = null
        music = null
        super.onDetachedFromWindow()
    }
}

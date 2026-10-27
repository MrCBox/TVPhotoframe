package com.leog.photoframe

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 轮播核心。主界面和屏保共用。
 *
 * 双 ImageView 交替：incoming 先置 alpha=0 提到最上层，等 Glide 解码完成后再淡入，
 * 同时 outgoing 淡出并立即释放 drawable —— 2GB 内存的电视上这一步很关键，
 * 否则两张 4K 位图会同时驻留。
 */
class SlideshowController(
    private val ctx: Context,
    private val imgA: ImageView,
    private val imgB: ImageView,
    private val clockView: TextView?,
    private val captionView: TextView?,
    private val onStatus: (String?) -> Unit
) {

    companion object {
        private const val FADE_MS = 900L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var album: Album? = null
    private val order = ArrayList<Int>()
    private var cursor = -1
    private var frontIsA = true
    private var paused = false
    private var night = false
    private var released = false

    private val slideRunnable = Runnable { advance(auto = true) }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000L)
        }
    }

    fun bind(album: Album) {
        this.album = album
        rebuildOrder()
        cursor = -1
    }

    fun start() {
        released = false
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
        advance(auto = false)
    }

    fun pause() {
        paused = true
        handler.removeCallbacks(slideRunnable)
    }

    fun resume() {
        paused = false
        scheduleNext()
    }

    fun isPaused(): Boolean = paused

    fun togglePause() {
        if (paused) resume() else pause()
    }

    /** 夜间息屏：暂停轮播，但保留时钟 */
    fun setNight(on: Boolean) {
        night = on
        if (on) {
            handler.removeCallbacks(slideRunnable)
        } else {
            scheduleNext()
        }
    }

    fun next() = advance(auto = false)

    fun prev() {
        val a = album ?: return
        if (a.photos.isEmpty() || order.isEmpty()) return
        cursor = if (cursor - 1 < 0) order.size - 1 else cursor - 1
        show(cursor)
        scheduleNext()
    }

    /** 设置变更后重排（例如改了随机播放） */
    fun reorderKeepCurrent() {
        val current = album?.photos?.takeIf { order.isNotEmpty() && cursor in order.indices }
            ?.get(order[cursor])
        rebuildOrder()
        val idx = current?.let { c -> album?.photos?.indexOf(c) ?: -1 } ?: -1
        cursor = if (idx >= 0) order.indexOf(idx).coerceAtLeast(0) else 0
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        imgA.animate().cancel()
        imgB.animate().cancel()
    }

    // ---------------------------------------------------------------- 内部

    private fun rebuildOrder() {
        order.clear()
        val n = album?.photos?.size ?: 0
        for (i in 0 until n) order.add(i)
        if (Prefs.shuffle) order.shuffle()
    }

    private fun scheduleNext() {
        handler.removeCallbacks(slideRunnable)
        if (paused || night || released) return
        handler.postDelayed(slideRunnable, Prefs.durationSec * 1000L)
    }

    private fun advance(auto: Boolean) {
        val a = album ?: return
        if (a.photos.isEmpty() || order.isEmpty()) {
            onStatus("相册为空")
            return
        }
        if (auto && (paused || night || released)) return

        if (cursor + 1 >= order.size) {
            if (Prefs.shuffle) rebuildOrder()
            cursor = 0
        } else {
            cursor += 1
        }
        show(cursor)
        scheduleNext()
    }

    private fun show(index: Int) {
        val a = album ?: return
        if (index !in order.indices) return
        val photo = a.photos[order[index]]

        onStatus(null)

        val incoming = if (frontIsA) imgB else imgA
        val outgoing = if (frontIsA) imgA else imgB
        frontIsA = !frontIsA

        incoming.animate().cancel()
        resetTransform(incoming)
        incoming.alpha = 0f
        incoming.bringToFront()
        incoming.tag = photo.url

        val (dw, dh) = decodeSize()

        Glide.with(ctx)
            .load(photo.url)
            .override(dw, dh)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (!released) {
                        handler.postDelayed({ advance(auto = false) }, 700L)
                    }
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    incoming.post {
                        if (released || incoming.tag != photo.url) return@post
                        incoming.alpha = 0f
                        incoming.animate()
                            .alpha(1f)
                            .setDuration(FADE_MS)
                            .start()

                        outgoing.animate()
                            .alpha(0f)
                            .setDuration(FADE_MS)
                            .withEndAction {
                                outgoing.setImageDrawable(null)
                                resetTransform(outgoing)
                            }
                            .start()

                        kenBurns(incoming, Prefs.durationSec * 1000L)
                        updateCaption(photo)
                    }
                    return false
                }
            })
            .into(incoming)
    }

    /**
     * 缓慢推拉。既是转场效果，也是防烧屏：每张图的起止缩放和落点都随机，
     * 同一点像素不会长时间固定点亮。
     */
    private fun kenBurns(v: ImageView, durationMs: Long) {
        if (!Prefs.antiBurnIn) {
            resetTransform(v)
            return
        }
        val url = v.tag
        v.post {
            if (released || v.tag != url) return@post
            val w = v.width.toFloat()
            val h = v.height.toFloat()
            if (w <= 0f || h <= 0f) return@post

            v.pivotX = w / 2f
            v.pivotY = h / 2f

            val start = 1.02f + rnd(0.02f)
            val end = 1.09f + rnd(0.06f)
            v.scaleX = start
            v.scaleY = start
            v.translationX = 0f
            v.translationY = 0f

            // 只用到溢出量的 40%，无论如何不会露白边
            val maxTx = (end - 1f) * w / 2f * 0.4f
            val maxTy = (end - 1f) * h / 2f * 0.4f
            val tx = (Math.random().toFloat() - 0.5f) * 2f * maxTx
            val ty = (Math.random().toFloat() - 0.5f) * 2f * maxTy

            v.animate()
                .scaleX(end)
                .scaleY(end)
                .translationX(tx)
                .translationY(ty)
                .setDuration(durationMs + 1400L)
                .setInterpolator(LinearInterpolator())
                .start()
        }
    }

    private fun resetTransform(v: ImageView) {
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
    }

    private fun rnd(range: Float): Float = Math.random().toFloat() * range

    /** 按设置里的解码宽度算目标尺寸，避免 4K 图整张解到内存 */
    private fun decodeSize(): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        val longSide = max(dm.widthPixels, dm.heightPixels)
        val shortSide = min(dm.widthPixels, dm.heightPixels)
        val cfg = Prefs.decodeWidth
        val targetW = if (cfg > 0) cfg else longSide
        val targetH = (targetW.toLong() * shortSide / longSide).toInt().coerceAtLeast(1)
        return targetW to targetH
    }

    private fun updateClock() {
        val c = clockView ?: return
        if (!Prefs.showClock) {
            c.visibility = View.GONE
            return
        }
        c.visibility = View.VISIBLE
        c.text = timeFormat.format(Date())
    }

    private fun updateCaption(photo: Photo) {
        val c = captionView ?: return
        if (!Prefs.showClock) {
            c.visibility = View.GONE
            return
        }
        val taken = photo.takenAt
        c.text = if (!taken.isNullOrBlank()) {
            taken.replace('T', ' ').take(16)
        } else {
            photo.name
        }
        c.visibility = View.VISIBLE
    }
}

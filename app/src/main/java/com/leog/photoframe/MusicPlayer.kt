package com.leog.photoframe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 背景音乐：从清单里的 music 列表顺序播放，切曲时淡入淡出，失败自动跳过下一首。
 *
 * 为什么先下载再播：uhttpd 不支持 Range 请求（只回 200，不回 206），
 * Android 的 MediaPlayer 对不支持 Range 的 HTTP 源兼容性不稳，
 * 常见表现是 prepare 失败或播到一半断掉。改成「下载到本地缓存再播」即绕开该限制，
 * 局域网下一首 8MB 的歌 2 秒内下完，且之后同一首歌秒开。
 *
 * 用 MediaPlayer 而不是 ExoPlayer —— 2GB 内存的电视上少背一个库更稳。
 */
class MusicPlayer(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val io = Executors.newSingleThreadExecutor()

    private var player: MediaPlayer? = null
    private var volumeAnim: ValueAnimator? = null

    private var playlist: List<String> = emptyList()
    private var cursor = -1
    private var wantPlaying = false
    private var targetVolume = 0.55f

    /** 递增令牌，用于丢弃过期的下载回调 */
    private var token = 0

    /** 连续失败计数，连续 5 首拿不到就停，避免无限重试白耗流量 */
    private var failStreak = 0

    fun setPlaylist(list: List<String>) {
        playlist = list
        cursor = -1
    }

    fun setVolume(v: Float) {
        targetVolume = v.coerceIn(0f, 1f)
        if (wantPlaying) {
            try {
                player?.setVolume(targetVolume, targetVolume)
            } catch (_: Exception) {
            }
        }
    }

    fun start() {
        if (playlist.isEmpty()) return
        wantPlaying = true
        if (player == null) playNext()
    }

    fun pause() {
        wantPlaying = false
        token++
        fadeTo(targetVolume, 0f, 700) { releasePlayer() }
    }

    fun resume() {
        if (playlist.isEmpty()) return
        wantPlaying = true
        if (player == null) playNext()
    }

    fun release() {
        wantPlaying = false
        token++
        volumeAnim?.cancel()
        releasePlayer()
    }

    // ---------------- 播放流程 ----------------

    private fun playNext() {
        if (!wantPlaying || playlist.isEmpty()) return
        releasePlayer()
        cursor = (cursor + 1) % playlist.size
        val url = playlist[cursor]
        val myToken = ++token

        io.execute {
            val file = ensureLocal(url)
            handler.post {
                if (myToken != token || !wantPlaying) return@post
                if (file == null) {
                    // 下载失败，隔一会儿换下一首，避免死循环卡在同一首
                    failStreak++
                    if (failStreak >= 5) {
                        wantPlaying = false
                        return@post
                    }
                    scheduleNext(2500)
                    return@post
                }
                playLocal(file, myToken)
            }
        }
    }

    private fun playLocal(file: File, myToken: Int) {
        if (!wantPlaying || myToken != token) return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                if (!wantPlaying || myToken != token) {
                    try {
                        p.release()
                    } catch (_: Exception) {
                    }
                    return@setOnPreparedListener
                }
                try {
                    p.setVolume(0f, 0f)
                    p.start()
                    failStreak = 0
                    fadeTo(0f, targetVolume, 1600)
                } catch (_: Exception) {
                    scheduleNext(1500)
                }
            }
            mp.setOnCompletionListener { playNext() }
            mp.setOnErrorListener { _, _, _ ->
                // 本地文件也放不了（编码不支持），删掉缓存下次重下
                try {
                    file.delete()
                } catch (_: Exception) {
                }
                scheduleNext(1500)
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            scheduleNext(2500)
        }
    }

    private fun scheduleNext(delayMs: Long) {
        releasePlayer()
        val myToken = ++token
        handler.postDelayed({
            if (myToken == token) playNext()
        }, delayMs)
    }

    // ---------------- 下载与缓存 ----------------

    private fun cacheFile(url: String): File {
        val raw = url.substringAfterLast('/').substringBefore('?')
        val name = raw.ifBlank { "audio" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(ctx.cacheDir, "music")
        return File(dir, "${Integer.toHexString(url.hashCode())}_$name")
    }

    /** 本地已有就直接用，否则同步下载（在 io 线程调用） */
    private fun ensureLocal(url: String): File? {
        val target = cacheFile(url)
        if (target.exists() && target.length() > 4096) return target

        val dir = target.parentFile ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val part = File(dir, target.name + ".part")
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                part.outputStream().use { out ->
                    body.byteStream().copyTo(out, 64 * 1024)
                }
            }
            if (part.length() <= 4096) {
                part.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return null
            target
        } catch (e: Exception) {
            try {
                part.delete()
            } catch (_: Exception) {
            }
            null
        }
    }

    /**
     * 缓存超过 400MB 就删掉最旧的一半。
     * 电视内部存储只有 32GB，别让音乐缓存无限长大。
     */
    fun trimCache() {
        io.execute {
            try {
                val dir = File(ctx.cacheDir, "music")
                val files = dir.listFiles() ?: return@execute
                var total = files.sumOf { it.length() }
                if (total < 400L * 1024 * 1024) return@execute
                files.sortedBy { it.lastModified() }
                    .take(files.size / 2)
                    .forEach { f ->
                        total -= f.length()
                        f.delete()
                        if (total < 200L * 1024 * 1024) return@execute
                    }
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- 音量渐变 ----------------

    private fun fadeTo(from: Float, to: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        volumeAnim?.cancel()
        if (player == null) {
            onEnd?.invoke()
            return
        }
        val anim = ValueAnimator.ofFloat(from, to)
        anim.duration = durationMs
        anim.addUpdateListener { a ->
            val v = a.animatedValue as Float
            try {
                player?.setVolume(v, v)
            } catch (_: Exception) {
            }
        }
        if (onEnd != null) {
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
        }
        volumeAnim = anim
        anim.start()
    }

    private fun releasePlayer() {
        volumeAnim?.cancel()
        volumeAnim = null
        player?.let {
            try {
                it.reset()
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }
}

package com.leog.photoframe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * 背景音乐：从清单里的 music 列表顺序播放，切曲时淡入淡出，失败自动跳过下一首。
 * 用 MediaPlayer 而不是 ExoPlayer —— 2GB 内存的电视上少背一个库更稳。
 */
class MusicPlayer(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var volumeAnim: ValueAnimator? = null

    private var playlist: List<String> = emptyList()
    private var cursor = -1
    private var wantPlaying = false
    private var targetVolume = 0.55f

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
        fadeTo(targetVolume, 0f, 700) { releasePlayer() }
    }

    fun resume() {
        if (playlist.isEmpty()) return
        wantPlaying = true
        if (player == null) playNext()
    }

    fun release() {
        wantPlaying = false
        volumeAnim?.cancel()
        releasePlayer()
    }

    private fun playNext() {
        if (!wantPlaying || playlist.isEmpty()) return
        releasePlayer()
        cursor = (cursor + 1) % playlist.size
        val url = playlist[cursor]

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { p ->
                try {
                    p.setVolume(0f, 0f)
                    p.start()
                    fadeTo(0f, targetVolume, 1600)
                } catch (_: Exception) {
                    scheduleNext(1500)
                }
            }
            mp.setOnCompletionListener { playNext() }
            mp.setOnErrorListener { _, _, _ ->
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
        handler.postDelayed({ playNext() }, delayMs)
    }

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

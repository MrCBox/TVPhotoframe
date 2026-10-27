package com.leog.photoframe

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局设置。用 SharedPreferences 保存，TV 上没有数据库依赖更轻。
 */
object Prefs {

    private const val FILE = "photoframe"
    private var _sp: SharedPreferences? = null

    private val sp: SharedPreferences
        get() = _sp ?: error("Prefs 未初始化")

    fun init(ctx: Context) {
        if (_sp == null) {
            _sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** 预填家里的默认地址，用户只需改端口即可 */
    const val DEFAULT_MANIFEST = "http://192.168.117.253:8089/manifest.json"

    /** 相册清单地址，例如 http://192.168.117.253:8089/manifest.json */
    var manifestUrl: String
        get() = sp.getString("manifest_url", DEFAULT_MANIFEST) ?: DEFAULT_MANIFEST
        set(v) = sp.edit().putString("manifest_url", v).apply()

    /** 每张停留秒数 */
    var durationSec: Int
        get() = sp.getInt("duration", 8)
        set(v) = sp.edit().putInt("duration", v).apply()

    /** 转场：random / fade / kenburns / slide */
    var transition: String
        get() = sp.getString("transition", "random") ?: "random"
        set(v) = sp.edit().putString("transition", v).apply()

    /** 解码宽度：0=跟随屏幕，1920 / 2560 / 3840 */
    var decodeWidth: Int
        get() = sp.getInt("decode_width", 2560)
        set(v) = sp.edit().putInt("decode_width", v).apply()

    var musicEnabled: Boolean
        get() = sp.getBoolean("music", true)
        set(v) = sp.edit().putBoolean("music", v).apply()

    /** 音量 0-100 */
    var volume: Int
        get() = sp.getInt("volume", 55)
        set(v) = sp.edit().putInt("volume", v).apply()

    /** 音乐淡入时长（秒），0 = 直接起音 */
    var fadeInSec: Int
        get() = sp.getInt("fade_in", 3)
        set(v) = sp.edit().putInt("fade_in", v).apply()

    /** 音乐淡出时长（秒），0 = 直接切断；用于每首歌结束前的收尾 */
    var fadeOutSec: Int
        get() = sp.getInt("fade_out", 4)
        set(v) = sp.edit().putInt("fade_out", v).apply()

    /** 音乐随机顺序：开启后每次载入相册都重新洗牌，起点不固定 */
    var musicShuffle: Boolean
        get() = sp.getBoolean("music_shuffle", true)
        set(v) = sp.edit().putBoolean("music_shuffle", v).apply()

    /** 防烧屏：开启后启用缓慢推拉，并让每张图的取景随机偏移 */
    var antiBurnIn: Boolean
        get() = sp.getBoolean("burnin", true)
        set(v) = sp.edit().putBoolean("burnin", v).apply()

    var nightMode: Boolean
        get() = sp.getBoolean("night", false)
        set(v) = sp.edit().putBoolean("night", v).apply()

    /** 夜间开始时刻，单位：分钟（23*60） */
    var nightStartMin: Int
        get() = sp.getInt("night_start", 23 * 60)
        set(v) = sp.edit().putInt("night_start", v).apply()

    var nightEndMin: Int
        get() = sp.getInt("night_end", 7 * 60)
        set(v) = sp.edit().putInt("night_end", v).apply()

    var showClock: Boolean
        get() = sp.getBoolean("clock", true)
        set(v) = sp.edit().putBoolean("clock", v).apply()

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", true)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    /** 设置页点了"重新载入相册"后置位，主界面回来时据此强制重拉清单 */
    var needReload: Boolean
        get() = sp.getBoolean("need_reload", false)
        set(v) = sp.edit().putBoolean("need_reload", v).apply()

    fun nightRangeText(): String =
        "%02d:00 - %02d:00".format(nightStartMin / 60, nightEndMin / 60)
}

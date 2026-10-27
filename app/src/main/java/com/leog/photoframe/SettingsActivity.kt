package com.leog.photoframe

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private data class SettingItem(val key: String, val title: String, var value: String)

class SettingsActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private val items = ArrayList<SettingItem>()
    private lateinit var adapter: SettingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = SettingAdapter(items) { onItemClick(it) }
        list.adapter = adapter

        buildItems()
        list.post { list.requestFocus() }
    }

    // ------------------------------------------------------------ 列表

    private fun buildItems() {
        items.clear()
        items.add(SettingItem("manifest", "相册清单地址", Prefs.manifestUrl))
        items.add(SettingItem("duration", "每张停留", "${Prefs.durationSec} 秒"))
        items.add(SettingItem("decode", "解码画质", decodeLabel(Prefs.decodeWidth)))
        items.add(SettingItem("music", "背景音乐", onOff(Prefs.musicEnabled)))
        items.add(SettingItem("volume", "音乐音量", "${Prefs.volume}%"))
        items.add(SettingItem("mshuffle", "音乐随机顺序", onOff(Prefs.musicShuffle)))
        items.add(SettingItem("fadein", "音乐淡入", fadeLabel(Prefs.fadeInSec)))
        items.add(SettingItem("fadeout", "音乐淡出", fadeLabel(Prefs.fadeOutSec)))
        items.add(SettingItem("burnin", "缓慢推近（兼防烧屏）", onOff(Prefs.antiBurnIn)))
        items.add(SettingItem("shuffle", "随机播放", onOff(Prefs.shuffle)))
        items.add(SettingItem("clock", "显示时钟与拍摄时间", onOff(Prefs.showClock)))
        items.add(SettingItem("night", "夜间息屏", onOff(Prefs.nightMode)))
        items.add(SettingItem("nightrange", "息屏时段", Prefs.nightRangeText()))
        items.add(SettingItem("dream", "设为系统屏保", "去系统设置"))
        items.add(SettingItem("reload", "重新载入相册", "点击执行"))
        adapter.notifyDataSetChanged()
    }

    private fun onOff(b: Boolean) = if (b) "开启" else "关闭"

    private fun fadeLabel(sec: Int) = if (sec <= 0) "关闭（直接切换）" else "$sec 秒"

    private fun decodeLabel(w: Int): String = when (w) {
        0 -> "跟随屏幕"
        1920 -> "1080P · 最省内存"
        2560 -> "2.5K · 推荐"
        3840 -> "4K · 最清晰"
        else -> "$w px"
    }

    // ------------------------------------------------------------ 点击

    private fun onItemClick(item: SettingItem) {
        when (item.key) {
            "manifest" -> editManifest()
            "duration" -> chooseNumber(
                "每张停留", intArrayOf(5, 8, 10, 15, 20, 30, 45, 60),
                Prefs.durationSec, { "$it 秒" }
            ) { Prefs.durationSec = it }
            "decode" -> chooseNumber(
                "解码画质", intArrayOf(1920, 2560, 3840, 0),
                Prefs.decodeWidth, { decodeLabel(it) }
            ) { Prefs.decodeWidth = it }
            "volume" -> chooseNumber(
                "音乐音量", intArrayOf(20, 40, 55, 70, 85, 100),
                Prefs.volume, { "$it%" }
            ) { Prefs.volume = it }
            "mshuffle" -> {
                Prefs.musicShuffle = !Prefs.musicShuffle
                refresh()
            }
            "fadein" -> chooseNumber(
                "音乐淡入（每首开始）", intArrayOf(0, 1, 2, 3, 5, 8),
                Prefs.fadeInSec, { fadeLabel(it) }
            ) { Prefs.fadeInSec = it }
            "fadeout" -> chooseNumber(
                "音乐淡出（每首结束前）", intArrayOf(0, 1, 2, 3, 4, 6, 8),
                Prefs.fadeOutSec, { fadeLabel(it) }
            ) { Prefs.fadeOutSec = it }
            "music" -> {
                Prefs.musicEnabled = !Prefs.musicEnabled
                refresh()
            }
            "burnin" -> {
                Prefs.antiBurnIn = !Prefs.antiBurnIn
                refresh()
            }
            "shuffle" -> {
                Prefs.shuffle = !Prefs.shuffle
                refresh()
            }
            "clock" -> {
                Prefs.showClock = !Prefs.showClock
                refresh()
            }
            "night" -> {
                Prefs.nightMode = !Prefs.nightMode
                refresh()
            }
            "nightrange" -> chooseNightRange()
            "dream" -> openDreamSettings()
            "reload" -> {
                Prefs.needReload = true
                finish()
            }
        }
    }

    private fun refresh() = buildItems()

    private fun editManifest() {
        val input = EditText(this).apply {
            setText(Prefs.manifestUrl)
            setSelection(text.length)
            isSingleLine = true
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHint("http://192.168.117.253:8089/manifest.json")
        }
        val wrap = FrameLayout(this).apply {
            setPadding(48, 28, 48, 8)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle("相册清单地址")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    Prefs.manifestUrl = v
                    Prefs.needReload = true
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseNumber(
        title: String,
        values: IntArray,
        current: Int,
        label: (Int) -> String,
        apply: (Int) -> Unit
    ) {
        val labels = values.map(label).toTypedArray()
        val cur = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, cur) { d, which ->
                apply(values[which])
                d.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseNightRange() {
        val starts = intArrayOf(22 * 60, 23 * 60, 0, 23 * 60)
        val ends = intArrayOf(7 * 60, 7 * 60, 6 * 60, 8 * 60)
        val labels = arrayOf("22:00 - 07:00", "23:00 - 07:00", "00:00 - 06:00", "23:00 - 08:00")
        var cur = 0
        for (i in starts.indices) {
            if (starts[i] == Prefs.nightStartMin && ends[i] == Prefs.nightEndMin) {
                cur = i
                break
            }
        }
        AlertDialog.Builder(this)
            .setTitle("息屏时段")
            .setSingleChoiceItems(labels, cur) { d, which ->
                Prefs.nightStartMin = starts[which]
                Prefs.nightEndMin = ends[which]
                d.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            Toast.makeText(this, "在系统列表里选择「乐格相框屏保」", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "这台电视没有打开屏保设置的入口", Toast.LENGTH_LONG).show()
        }
    }
}

private class SettingAdapter(
    private val items: List<SettingItem>,
    private val onClick: (SettingItem) -> Unit
) : RecyclerView.Adapter<SettingAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val value: TextView = v.findViewById(R.id.value)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.value.text = item.value
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v.parent as? RecyclerView)?.smoothScrollToPosition(position)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}

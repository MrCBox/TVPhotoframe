package com.leog.photoframe

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 从 N1 上的静态服务拉取 manifest.json。
 *
 * 清单格式（宽松兼容）：
 * {
 *   "photos": [
 *     "2023/2023-05/DSC01234.jpg",
 *     { "f": "2023/2023-05/DSC01235.jpg", "t": "2023-05-01 10:23:11", "w": 3840, "h": 2560 }
 *   ],
 *   "music": ["bgm/01.mp3", "bgm/02.mp3"]
 * }
 *
 * photos 里每项可以是字符串，也可以是对象；相对路径都相对于 manifest 所在目录。
 */
class ManifestRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun load(rawUrl: String): Album = withContext(Dispatchers.IO) {
        val url = normalize(rawUrl)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, */*")
            .build()

        val body = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                }
                resp.body?.string() ?: throw IOException("响应为空")
            }
        } catch (e: IOException) {
            throw IOException("连不上 $url（${e.message}）", e)
        }

        parse(body, url)
    }

    private fun normalize(input: String): String {
        var u = input.trim()
        if (u.isEmpty()) throw IOException("相册清单地址未设置")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "http://$u"
        }
        // 允许只填目录，自动补 manifest.json
        if (!u.endsWith(".json", true)) {
            u = u.trimEnd('/') + "/manifest.json"
        }
        return u
    }

    private fun parse(json: String, manifestUrl: String): Album {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IOException("清单不是合法 JSON")
        }
        val base = manifestUrl.substringBeforeLast('/') + "/"

        val photos = ArrayList<Photo>()
        val photosArr = root.optJSONArray("photos") ?: JSONArray()
        for (i in 0 until photosArr.length()) {
            when (val item = photosArr.opt(i)) {
                is String -> {
                    if (item.isNotBlank()) photos.add(buildPhoto(base, item, null, 0, 0))
                }
                is JSONObject -> {
                    val rel = item.optString("f").ifBlank { item.optString("path") }
                        .ifBlank { item.optString("file") }
                    if (rel.isBlank()) continue
                    val taken = item.optString("t").ifBlank { item.optString("taken") }
                        .ifBlank { null }
                    photos.add(
                        buildPhoto(base, rel, taken, item.optInt("w", 0), item.optInt("h", 0))
                    )
                }
                else -> Unit
            }
        }

        val music = ArrayList<String>()
        val musicArr = root.optJSONArray("music") ?: JSONArray()
        for (i in 0 until musicArr.length()) {
            val m = musicArr.optString(i, "")
            if (m.isNotBlank()) music.add(joinUrl(base, m))
        }

        return Album(photos, music)
    }

    private fun buildPhoto(base: String, rel: String, taken: String?, w: Int, h: Int): Photo =
        Photo(
            url = joinUrl(base, rel),
            name = rel.substringAfterLast('/'),
            takenAt = taken,
            width = w,
            height = h
        )

    /** 逐段 URL 编码，中文目录名也不会 404 */
    private fun joinUrl(base: String, rel: String): String {
        val b = if (base.endsWith("/")) base else "$base/"
        val encoded = rel.trimStart('/')
            .split('/')
            .joinToString("/") { Uri.encode(it) }
        return b + encoded
    }
}

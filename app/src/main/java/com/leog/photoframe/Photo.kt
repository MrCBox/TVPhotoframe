package com.leog.photoframe

/** 一张照片。url 已拼好可直接交给 Glide。 */
data class Photo(
    val url: String,
    val name: String,
    val takenAt: String? = null,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * 一份相册清单：照片列表 + 可选背景音乐列表。
 *
 * version 由照片路径列表算出，作为 Glide 的缓存签名：
 * 只要清单内容变了（增删改名），旧缓存自动失效，不会出现"删掉的照片还在播"；
 * 清单没变时签名不变，缓存继续命中，不会每次重下几百 MB。
 */
data class Album(
    val photos: List<Photo>,
    val music: List<String> = emptyList(),
    val version: String = ""
)

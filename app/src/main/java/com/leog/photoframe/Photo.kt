package com.leog.photoframe

/** 一张照片。url 已拼好可直接交给 Glide。 */
data class Photo(
    val url: String,
    val name: String,
    val takenAt: String? = null,
    val width: Int = 0,
    val height: Int = 0
)

/** 一份相册清单：照片列表 + 可选背景音乐列表。 */
data class Album(
    val photos: List<Photo>,
    val music: List<String> = emptyList()
)

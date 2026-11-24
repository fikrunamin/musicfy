// Thumbnails.kt

package com.music.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnails(
    val thumbnails: List<Thumbnail>,
)

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int?,
    val height: Int?,
)

/**
 * The first thumbnail at least [minWidth] wide (YouTube lists them small to large), not the largest;
 * when none is that wide, the largest there is (the last one when no widths are given).
 */
fun List<Thumbnail>.firstAtLeast(minWidth: Int): Thumbnail? =
    firstOrNull { (it.width ?: 0) >= minWidth } ?: asReversed().maxByOrNull { it.width ?: 0 }

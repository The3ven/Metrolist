package com.metrolist.music.jiosaavn

/** A track returned from a JioSaavn search. */
data class JioSaavnTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val permaUrl: String?,
)

package com.metrolist.music.audius

/** A track returned from an Audius search. */
data class AudiusTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
    val permaUrl: String?,
)

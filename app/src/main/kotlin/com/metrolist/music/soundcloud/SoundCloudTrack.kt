package com.metrolist.music.soundcloud

/** A track returned from a SoundCloud search. */
data class SoundCloudTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnail: String?,
    val permaUrl: String?,
)

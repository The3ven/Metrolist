package com.metrolist.music.share

import com.metrolist.music.discord.DiscordDefaults

/** Builds the link embedded in a shared story (with a tracking source tag). */
object ShareLink {
    fun forStory(videoId: String): String =
        "${DiscordDefaults.YOUTUBE_WATCH_URL}$videoId&utm_source=metrolist_story"
}

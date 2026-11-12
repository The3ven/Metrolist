package com.metrolist.music.share

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import com.metrolist.music.R
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Font choices offered for the story poster. */
enum class StoryFont(@StringRes val labelRes: Int) {
    APP(R.string.story_font_app),
    SANS(R.string.story_font_sans),
    SERIF(R.string.story_font_serif),
    MONO(R.string.story_font_mono);

    fun resolve(context: Context): StoryTypefaces =
        cache.getOrPut(this) {
            when (this) {
                APP -> appFaces(context)
                SANS -> systemFaces(SANS_REGULAR, SANS_BOLD, Typeface.SANS_SERIF)
                SERIF -> systemFaces(SERIF_REGULAR, SERIF_BOLD, Typeface.SERIF)
                MONO -> systemFaces(MONO_REGULAR, MONO_BOLD, Typeface.MONOSPACE)
            }
        }

    private fun appFaces(context: Context): StoryTypefaces {
        val typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.bbh_bartle_regular)
        }.getOrNull() ?: Typeface.DEFAULT
        return StoryTypefaces(typeface, typeface, syntheticBold = true)
    }

    private fun systemFaces(
        regularNames: List<String>,
        boldNames: List<String>,
        fallback: Typeface,
    ): StoryTypefaces {
        val regular = loadFirst(regularNames) ?: fallback
        val bold = loadFirst(boldNames)
        return StoryTypefaces(
            regular = regular,
            bold = bold ?: regular,
            syntheticBold = bold == null,
        )
    }

    private fun loadFirst(names: List<String>): Typeface? {
        for (name in names) {
            val file = File(SYSTEM_FONT_DIR, name)
            if (file.exists()) {
                val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
                if (typeface != null) return typeface
                Timber.w("Found %s but could not load it as a typeface", file)
            }
        }
        return null
    }

    companion object {
        private const val SYSTEM_FONT_DIR = "/system/fonts"
        private val SANS_REGULAR = listOf("Roboto-Regular.ttf", "DroidSans.ttf")
        private val SANS_BOLD = listOf("Roboto-Bold.ttf", "DroidSans-Bold.ttf")
        private val SERIF_REGULAR = listOf("NotoSerif-Regular.ttf", "Tinos-Regular.ttf", "DroidSerif-Regular.ttf")
        private val SERIF_BOLD = listOf("NotoSerif-Bold.ttf", "Tinos-Bold.ttf", "DroidSerif-Bold.ttf")
        private val MONO_REGULAR = listOf("RobotoMono-Regular.ttf", "DroidSansMono.ttf", "CutiveMono.ttf")
        private val MONO_BOLD = listOf("RobotoMono-Bold.ttf")
        private val cache = ConcurrentHashMap<StoryFont, StoryTypefaces>()
    }
}

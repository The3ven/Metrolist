package com.metrolist.music.share

import android.graphics.Typeface

/**
 * A resolved pair of typefaces for the story poster.
 * @param syntheticBold true when no real bold face was found and the bold text
 * should be emulated via [android.text.TextPaint.setFakeBoldText].
 */
data class StoryTypefaces(
    val regular: Typeface,
    val bold: Typeface,
    val syntheticBold: Boolean,
)

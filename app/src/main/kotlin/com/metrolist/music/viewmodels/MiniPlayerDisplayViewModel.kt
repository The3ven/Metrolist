package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import com.metrolist.music.displaybridge.DisplayBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MiniPlayerDisplayViewModel @Inject constructor(
    val displayBridge: DisplayBridge,
) : ViewModel() {
    val status get() = displayBridge.status

    fun retry() = displayBridge.retry()
}

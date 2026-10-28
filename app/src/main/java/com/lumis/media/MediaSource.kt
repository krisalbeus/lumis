package com.lumis.media

import android.net.Uri

sealed interface MediaSource {
    data object BundledSample : MediaSource
    data class Video(val uri: Uri) : MediaSource
    data class Image(val uri: Uri) : MediaSource
}

enum class MediaFailure {
    UNAVAILABLE,
    UNSUPPORTED,
    DECODER,
}

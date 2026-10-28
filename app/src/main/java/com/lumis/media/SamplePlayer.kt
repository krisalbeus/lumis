package com.lumis.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.lumis.R
import java.io.FileNotFoundException
import java.io.IOException

class SamplePlayer(
    private val context: Context,
    private val onState: (Boolean, Boolean) -> Unit,
    private val onFailure: (MediaFailure) -> Unit,
) {
    private var player: MediaPlayer? = null
    private var prepared = false
    var source: MediaSource = MediaSource.BundledSample
        private set
    var wantsToPlay = true
        private set
    var resumePosition = 0
        private set

    fun setSource(source: MediaSource, resetPosition: Boolean = true) {
        require(source is MediaSource.BundledSample || source is MediaSource.Video) {
            "SamplePlayer only supports video sources."
        }
        this.source = source
        if (resetPosition) resumePosition = 0
    }

    fun restore(playing: Boolean, position: Int) {
        wantsToPlay = playing
        resumePosition = position.coerceAtLeast(0)
    }

    fun attach(surface: Surface) {
        release()
        onState(false, false)
        val next = MediaPlayer()
        player = next
        try {
            require(surface.isValid) { "Video output surface is unavailable." }
            when (val currentSource = source) {
                MediaSource.BundledSample -> context.resources.openRawResourceFd(R.raw.prism_waves).use {
                    next.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                is MediaSource.Video -> next.setDataSource(context, currentSource.uri)
                is MediaSource.Image -> error("Unexpected image source in SamplePlayer.")
            }
            next.setSurface(surface)
            next.isLooping = true
            next.setVolume(0f, 0f)
            next.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                it.seekTo(resumePosition)
                if (wantsToPlay) it.start()
                onState(true, wantsToPlay)
            }
            next.setOnErrorListener { _, what, extra ->
                Log.e("LumisPlayer", "Video decoder error: what=$what extra=$extra")
                release(preservePosition = false)
                onFailure(MediaFailure.DECODER)
                true
            }
            next.prepareAsync()
        } catch (error: SecurityException) {
            fail(error, MediaFailure.UNAVAILABLE)
        } catch (error: FileNotFoundException) {
            fail(error, MediaFailure.UNAVAILABLE)
        } catch (error: IOException) {
            fail(error, MediaFailure.UNSUPPORTED)
        } catch (error: IllegalStateException) {
            fail(error, MediaFailure.DECODER)
        } catch (error: IllegalArgumentException) {
            fail(error, MediaFailure.UNSUPPORTED)
        }
    }

    fun toggle() {
        if (!prepared) return
        wantsToPlay = !wantsToPlay
        try {
            if (wantsToPlay) player?.start() else player?.pause()
            onState(true, wantsToPlay)
        } catch (error: IllegalStateException) {
            fail(error, MediaFailure.DECODER)
        }
    }

    fun position(): Int {
        if (!prepared) return resumePosition
        return try {
            player?.currentPosition ?: resumePosition
        } catch (error: IllegalStateException) {
            Log.e("LumisPlayer", "Decoder position unavailable; retaining last known position", error)
            resumePosition
        }
    }

    fun release(preservePosition: Boolean = true) {
        if (prepared && preservePosition) resumePosition = position()
        prepared = false
        player?.release()
        player = null
    }

    private fun fail(error: Exception, failure: MediaFailure) {
        Log.e("LumisPlayer", "Unable to play bundled sample", error)
        release(preservePosition = false)
        onFailure(failure)
    }
}

package com.lumis

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumis.media.MediaSource
import com.lumis.renderer.VideoRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class MediaImportTest {
    @Test fun importingImageKeepsSceneAndSwitchesToStillSource() = withApp { scenario ->
        val image = createTempImageUri(scenario)
        val before = read(scenario) { it.quad }
        read(scenario) { it.importFromUri(image) }
        await(scenario) {
            it.currentSource is MediaSource.Image && it.renderer.contentType == VideoRenderer.ContentType.IMAGE
        }
        assertEquals(before, read(scenario) { it.quad })
        assertEquals("Still image", read(scenario) { it.findViewById<TextView>(R.id.playback_state).text.toString() })
        assertFalse(read(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled })
        assertTrue(read(scenario) { it.findViewById<TextView>(R.id.source_detail).text.toString().contains("Imported image") })
    }

    @Test fun importingVideoKeepsSceneAndPlaybackAdvances() = withApp { scenario ->
        val video = createTempVideoUri(scenario)
        val handle = bounds(scenario, R.id.corner_b)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() - 45f, handle.centerY() + 35f)
        val edited = read(scenario) { it.quad }
        val beforeFrames = read(scenario) { it.renderer.videoFrames }
        read(scenario) { it.importFromUri(video) }
        await(scenario) {
            it.currentSource is MediaSource.Video &&
                it.renderer.contentType == VideoRenderer.ContentType.VIDEO &&
                it.findViewById<Button>(R.id.play_pause).isEnabled
        }
        await(scenario) { it.renderer.videoFrames > beforeFrames + 8 }
        assertEquals(edited, read(scenario) { it.quad })
        assertTrue(read(scenario) { it.findViewById<TextView>(R.id.source_detail).text.toString().contains("Imported video") })
    }

    @Test fun unsupportedImportShowsErrorAndPreservesCurrentMedia() = withApp { scenario ->
        val video = createTempVideoUri(scenario)
        val image = createTempImageUri(scenario)
        read(scenario) { it.importFromUri(image) }
        await(scenario) { it.currentSource is MediaSource.Image }
        val current = read(scenario) { it.currentSource }
        val edited = read(scenario) { it.quad }
        read(scenario) { it.importFromUri(unsupportedResourceUri()) }
        await(scenario) { it.findViewById<View>(R.id.error_panel).visibility == View.VISIBLE }
        assertEquals("Retry", read(scenario) { it.findViewById<Button>(R.id.retry_playback).text.toString() })
        assertEquals(current, read(scenario) { it.currentSource })
        assertEquals(edited, read(scenario) { it.quad })
        read(scenario) { it.importFromUri(video) }
        await(scenario) { it.findViewById<View>(R.id.error_panel).visibility == View.GONE && it.currentSource is MediaSource.Video }
    }

    @Test fun unavailableImportKeepsExistingMediaAndMapping() = withApp { scenario ->
        val video = createTempVideoUri(scenario)
        val image = createTempImageUri(scenario)
        read(scenario) { it.importFromUri(image) }
        await(scenario) { it.currentSource is MediaSource.Image }
        val current = read(scenario) { it.currentSource }
        val edited = read(scenario) { it.quad }
        read(scenario) { it.importFromUri(Uri.parse("content://com.lumis.missing/nope.png")) }
        await(scenario) { it.findViewById<View>(R.id.error_panel).visibility == View.VISIBLE }
        assertEquals(current, read(scenario) { it.currentSource })
        assertEquals(edited, read(scenario) { it.quad })
        read(scenario) { it.importFromUri(video) }
        await(scenario) { it.findViewById<View>(R.id.error_panel).visibility == View.GONE && it.currentSource is MediaSource.Video }
    }

    @Test fun missingRestoredImageFallsBackToSampleAndCanBeReplaced() = withApp { scenario ->
        val video = createTempVideoUri(scenario)
        val file = read(scenario) { activity ->
            val path = File(activity.cacheDir, "import-image-${SystemClock.uptimeMillis()}.png")
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF26C6DA.toInt()) }
            FileOutputStream(path).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            bitmap.recycle()
            path
        }
        read(scenario) { it.importFromUri(Uri.fromFile(file)) }
        await(scenario) { it.currentSource is MediaSource.Image }
        assertTrue(file.delete())
        val before = read(scenario) { it.quad }
        scenario.recreate()
        await(scenario) { it.findViewById<View>(R.id.error_panel).visibility == View.VISIBLE }
        assertTrue(read(scenario) { it.currentSource is MediaSource.BundledSample })
        assertEquals(before, read(scenario) { it.quad })
        val retryText = read(scenario) { it.findViewById<Button>(R.id.retry_playback).text.toString() }
        assertEquals("Replace media", retryText)
        read(scenario) { it.importFromUri(video) }
        await(scenario) { it.currentSource is MediaSource.Video && it.findViewById<View>(R.id.error_panel).visibility == View.GONE }
        val frames = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > frames + 6 }
    }

    private fun unsupportedResourceUri() = Uri.parse("android.resource://com.lumis/xml/data_extraction_rules")

    private fun createTempImageUri(scenario: androidx.test.core.app.ActivityScenario<MainActivity>): Uri = read(scenario) { activity ->
        val path = File(activity.cacheDir, "import-image-${SystemClock.uptimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF26C6DA.toInt()) }
        FileOutputStream(path).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
        Uri.fromFile(path)
    }

    private fun createTempVideoUri(scenario: androidx.test.core.app.ActivityScenario<MainActivity>): Uri = read(scenario) { activity ->
        val path = File(activity.cacheDir, "import-video-${SystemClock.uptimeMillis()}.mp4")
        activity.resources.openRawResource(R.raw.prism_waves).use { input ->
            FileOutputStream(path).use { output -> input.copyTo(output) }
        }
        Uri.fromFile(path)
    }
}

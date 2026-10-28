package com.lumis

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumis.editor.PreviewTransform
import com.lumis.geometry.Homography
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.media.MediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CompositionStressTest {
    @Test fun oneVideoSurvivesRepeatedEditingOutputAndBackgroundCycles() = stress(1, 0)
    @Test fun fourVideosKeepIndependentDecodersAdvancingAcrossCycles() = stress(4, 0)
    @Test fun eightDistinctImagesKeepTheirActualGpuPixelsAcrossCycles() = stress(0, 8)
    @Test fun fourVideosAndEightImagesComposeTogetherAcrossCycles() = stress(4, 8)

    private fun stress(videos: Int, images: Int) = withApp { scenario ->
        val start = SystemClock.elapsedRealtime()
        val initialPss = Debug.getPss()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
            Color.CYAN, Color.MAGENTA, Color.WHITE, Color.rgb(255, 128, 0))
        val uris = colors.take(images).mapIndexed { i, color ->
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stress-${System.nanoTime()}-$i.png")
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            Uri.fromFile(file)
        }
        scenario.onActivity { activity ->
            if (videos == 0) activity.importFromUri(uris.first())
            else repeat(videos - 1) { activity.addSurface() }
            val videoWidth = if (images > 0) .45 else .9
            activity.scene.surfaces.toList().forEachIndexed { index, layer ->
                val col = index % 2
                val row = index / 2
                activity.updateSurface(layer.copy(quad = rect(.05 + col * videoWidth / 2, .08 + row * .44,
                    .05 + col * videoWidth / 2 + videoWidth / 2 - .02, .08 + row * .44 + .4)))
            }
            uris.forEachIndexed { index, uri ->
                if (videos > 0 || index > 0) activity.addSurface(MediaSource.Image(uri))
                val offset = if (videos > 0) .53 else .05
                val tileWidth = if (videos > 0) .1 else .21
                val col = index % 4
                val row = index / 4
                activity.updateSurface(activity.scene.selected!!.copy(quad = rect(offset + col * tileWidth, .1 + row * .45,
                    offset + col * tileWidth + tileWidth - .015, .49 + row * .45)))
            }
            activity.selectSurface(activity.scene.surfaces.first().id)
        }
        val scene = read(scenario) { it.scene }
        assertEquals(videos + images, scene.surfaces.size)
        val videoIds = scene.surfaces.filter { it.source !is MediaSource.Image }.map { it.id }
        repeat(5) { cycle ->
            await(scenario) { a -> videoIds.all { a.renderer.framesFor(it) > 3 } &&
                a.scene.surfaces.all { a.renderer.surfaceFor(it.id) != null } }
            val frames = read(scenario) { a -> videoIds.associateWith { a.renderer.framesFor(it) } }
            scenario.onActivity { a ->
                repeat(12) { edit ->
                    val layer = a.scene.surfaces[(cycle + edit) % a.scene.surfaces.size]
                    a.updateSurface(layer.copy(opacity = .3f + edit / 20f))
                    a.undoEdit()
                    a.redoEdit()
                    a.undoEdit()
                }
                a.setPreviewTransform(PreviewTransform(1.25, .02, -.01))
                a.setOutputMode(true)
            }
            await(scenario) { a -> videoIds.all { a.renderer.framesFor(it) > frames.getValue(it) + 15 } }
            val deadline = SystemClock.uptimeMillis() + 12000
            var correct: Boolean
            do {
                val image = capture(scenario)
                val viewport = read(scenario) { it.renderer.viewport }
                correct = scene.surfaces.withIndex().all { (index, layer) ->
                    val isImage = layer.source is MediaSource.Image
                    val point = Homography(layer.quad).map(if (isImage) Point(.5, .5) else Point(.05, .05))
                    val p = viewport.toScreen(point)
                    val actual = image.getPixel(p.x.roundToInt(), p.y.roundToInt())
                    val expected = if (isImage) colors[index - videos] else Color.rgb(103, 232, 249)
                    listOf(0, 8, 16).all { shift -> abs((expected shr shift and 255) - (actual shr shift and 255)) <= if (isImage) 3 else 22 }
                }
                image.recycle()
                if (!correct) SystemClock.sleep(50)
            } while (!correct && SystemClock.uptimeMillis() < deadline)
            assertTrue("Every surface must produce the expected GPU pixels at cycle $cycle ($videos videos/$images images)", correct)
            assertEquals(scene, read(scenario) { it.scene })
            assertFalse(read(scenario) { it.findViewById<View>(R.id.layers).isShown })
            scenario.onActivity { it.setOutputMode(false) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        await(scenario) { a -> videoIds.all { a.renderer.framesFor(it) > 3 } }
        assertEquals(scene, read(scenario) { it.scene })
        Log.i("LumisStress", "$videos videos/$images images, five cycles, elapsedMs=${SystemClock.elapsedRealtime() - start}, " +
            "processPssKb=$initialPss->${Debug.getPss()}; emulator observation, not physical-device performance")
    }

    private fun rect(l: Double, t: Double, r: Double, b: Double) = Quad(Point(l, t), Point(r, t), Point(r, b), Point(l, b))
}

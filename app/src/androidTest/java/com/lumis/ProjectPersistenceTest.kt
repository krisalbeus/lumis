package com.lumis

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.media.MediaSource
import com.lumis.project.PlaybackState
import com.lumis.project.ProjectFormatException
import com.lumis.project.ProjectIo
import com.lumis.project.ProjectState
import com.lumis.project.ProjectStore
import com.lumis.scene.Scene
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val directory get() = File(context.filesDir, "projects")
    private val store get() = ProjectStore(directory)

    @Test fun versionedRoundTripPreservesAllSceneFieldsAndPlayback() = isolated { store, _ ->
        val scene = Scene().add(MediaSource.Image(Uri.parse("content://media/image/1"))).let {
            it.update(it.selected!!.copy(quad = warped, opacity = .35f, visible = false))
        }.add(MediaSource.Video(Uri.parse("content://media/video/2"))).move(1, true).select(2)
        val state = ProjectState(scene, mapOf(1L to PlaybackState(false, 3210), 2L to PlaybackState(), 3L to PlaybackState(true, 550)),
            mapOf(1L to MediaSource.Video(Uri.parse("content://media/missing"))))
        val project = store.create("  Studio wall  ", state)
        val loaded = store.load(project.id)
        assertEquals("Studio wall", loaded.project.name)
        assertEquals(state, loaded.project.state)
        assertFalse(loaded.recovered)
        assertEquals(listOf(project.id), store.list().map { it.id })
        val duplicate = store.duplicate(project.id)
        assertNotEquals(project.id, duplicate.id)
        assertEquals(state, duplicate.state)
        store.rename(duplicate.id, "Copy")
        store.delete(project.id)
        assertEquals("Copy", store.load(duplicate.id).project.name)
        assertEquals(1, store.list().size)
    }

    @Test fun emptySceneAndStableNextIdSurviveDiskRoundTrip() = isolated { store, _ ->
        val scene = Scene().delete(1)
        val project = store.create("Empty", ProjectState(scene))
        assertEquals(scene, store.load(project.id).project.state.scene)
        assertEquals(2L, store.load(project.id).project.state.scene.add().selectedId)
    }

    @Test fun corruptionUsesPreviousValidSnapshotWithoutOverwritingEitherFile() = isolated { store, dir ->
        val original = store.create("Recovery")
        store.save(original.copy(state = ProjectState(Scene().add())))
        val file = File(dir, "${original.id}.json")
        file.writeText("{truncated")
        val backup = File(dir, "${original.id}.recovery").readBytes()
        val loaded = store.load(original.id)
        assertTrue(loaded.recovered)
        assertEquals(original.state.scene, loaded.project.state.scene)
        assertEquals("{truncated", file.readText())
        assertArrayEquals(backup, File(dir, "${original.id}.recovery").readBytes())
        store.save(loaded.project)
        assertFalse(store.load(original.id).recovered)
        store.delete(original.id)
        assertTrue(store.list().isEmpty())
    }

    @Test fun unsupportedVersionAndInvalidGeometryAreExplicitAndNeverOverwritten() = isolated { store, dir ->
        val project = store.create("Future")
        val file = File(dir, "${project.id}.json")
        val json = JSONObject(file.readText()).put("version", 999)
        file.writeText(json.toString())
        expectFormatFailure { store.load(project.id) }
        assertEquals(999, JSONObject(file.readText()).getInt("version"))
        assertTrue(store.list().single().problem)
        json.put("version", 1)
        json.getJSONArray("surfaces").getJSONObject(0).put("opacity", -1)
        file.writeText(json.toString())
        expectFormatFailure { store.load(project.id) }
    }

    @Test fun failedWritesKeepPreviousProjectAndNamesAreValidated() = isolated { store, dir ->
        val project = store.create("Safe")
        val recovery = File(dir, "${project.id}.recovery")
        assertTrue(recovery.mkdir())
        val blocker = File(recovery, "blocker").apply { writeText("test") }
        try {
            try { store.save(project.copy(name = "Lost")); fail("Write must fail explicitly") } catch (_: IOException) { }
            assertEquals("Safe", store.load(project.id).project.name)
        } finally { assertTrue(blocker.delete()); assertTrue(recovery.delete()) }
        try { store.create("   "); fail("Blank name must be rejected") } catch (_: IllegalArgumentException) { }
        try { store.create("x".repeat(101)); fail("Overlong name must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun coldReopenRestoresMixedScenePausedIntentAndActualGpuImage() {
        val image = imageFixture()
        val state = ProjectState(Scene().add(MediaSource.Image(image)).let {
            it.update(it.selected!!.copy(quad = warped, opacity = .75f))
        }, mapOf(1L to PlaybackState(false, 1234), 2L to PlaybackState()))
        val project = io { store.create("Cold reopen ${System.nanoTime()}", state) }
        launch(project.id).use { scenario ->
            await(scenario) { it.scene == state.scene && it.renderer.framesFor(1) > 0 }
            scenario.onActivity { it.selectSurface(1) }
            await(scenario) { it.findViewById<android.view.View>(R.id.play_pause).isEnabled }
            assertFalse(read(scenario) { it.player.wantsToPlay })
            assertEquals(1234, read(scenario) { it.player.resumePosition })
            assertTrue(kotlin.math.abs(1234 - read(scenario) { it.player.position() }) < 80)
            scenario.onActivity { it.selectSurface(2); it.updateSurface(it.scene.selected!!.copy(opacity = 1f)) }
            awaitSaved(scenario)
        }
        launch(project.id).use { scenario ->
            await(scenario) { it.scene.selectedId == 2L && it.renderer.framesFor(1) > 0 }
            assertEquals(warped, read(scenario) { it.scene.selected!!.quad })
            val bitmap = capture(scenario)
            val p = read(scenario) { it.renderer.viewport.toScreen(Point(.5, .5)) }
            val color = bitmap.getPixel(p.x.roundToInt(), p.y.roundToInt())
            bitmap.recycle()
            assertTrue(Color.red(color) > 250 && Color.blue(color) < 4)
            scenario.onActivity { it.selectSurface(1) }
            await(scenario) { it.findViewById<android.view.View>(R.id.play_pause).isEnabled }
            assertFalse(read(scenario) { it.player.wantsToPlay })
            assertTrue(kotlin.math.abs(1234 - read(scenario) { it.player.position() }) < 80)
            assertFalse(read(scenario) { it.outputMode })
        }
    }

    @Test fun autosaveAndBackgroundFlushPersistLatestEditsWithoutRecreationBundle() = withApp { scenario ->
        scenario.onActivity {
            it.updateSurface(it.scene.selected!!.copy(quad = warped, opacity = .42f, visible = false))
            it.addSurface()
            it.selectSurface(1)
        }
        click(scenario, R.id.play_pause)
        val expected = read(scenario) { it.scene }
        val id = read(scenario) { it.currentProjectId!! }
        awaitSaved(scenario)
        val saved = io { store.load(id).project }
        assertEquals(expected, saved.state.scene)
        assertFalse(saved.state.playback.getValue(1).playing)
        scenario.onActivity { it.updateSurface(it.scene.selected!!.copy(opacity = .8f)) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertEquals(.8f, io { store.load(id).project.state.scene.selected!!.opacity })
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await(scenario) { it.renderer.framesFor(2) > 2 }
    }

    @Test fun recoveryRequiresConfirmationBeforeOverwritingCorruptProject() {
        val project = io {
            val p = store.create("Recover me ${System.nanoTime()}")
            store.save(p.copy(state = ProjectState(Scene().add())))
            File(directory, "${p.id}.json").writeText("broken")
            p
        }
        launch(project.id).use { scenario ->
            text("Recover project?")
            assertFalse(read(scenario) { it.editorReady })
            assertEquals("broken", io { File(directory, "${project.id}.json").readText() })
            text("Recover").click()
            await(scenario) { it.scene.surfaces.size == 1 && it.renderer.videoFrames > 2 }
            awaitSaved(scenario)
        }
        assertFalse(io { store.load(project.id).recovered })
    }

    @Test fun unreadableProjectWithoutBackupShowsLoadErrorAndKeepsOriginalBytes() {
        val project = io {
            val p = store.create("Invalid ${System.nanoTime()}")
            File(directory, "${p.id}.json").writeText("unreadable")
            p
        }
        launch(project.id).use { scenario ->
            text("Could not open project")
            assertFalse(read(scenario) { it.editorReady })
            assertEquals("unreadable", io { File(directory, "${project.id}.json").readText() })
        }
    }

    @Test fun missingMediaOnColdOpenPreservesMappingAndTheReplacePromptAcrossSaves() {
        val image = imageFixture()
        val scene = Scene().add(MediaSource.Image(image)).let { it.update(it.selected!!.copy(quad = warped, opacity = .6f)) }
        val project = io {
            val p = store.create("Missing ${System.nanoTime()}", ProjectState(scene))
            check(File(requireNotNull(image.path)).delete())
            p
        }
        launch(project.id).use { scenario ->
            await(scenario) { it.findViewById<android.view.View>(R.id.error_panel).isShown && it.renderer.framesFor(2) > 2 }
            assertEquals(warped, read(scenario) { it.quad })
            awaitSaved(scenario)
            assertEquals(MediaSource.Image(image), io { store.load(project.id).project.state.missing[2] })
        }
        launch(project.id).use { scenario ->
            await(scenario) { it.findViewById<android.view.View>(R.id.error_panel).isShown && it.renderer.framesFor(2) > 2 }
            assertEquals(warped, read(scenario) { it.quad })
            assertEquals("Replace media", read(scenario) { it.findViewById<TextView>(R.id.retry_playback).text.toString() })
        }
    }

    @Test fun rapidQueuedSavesCannotOverwriteTheNewestEditWithAnOlderSnapshot() = withApp { scenario ->
        scenario.onActivity { activity ->
            repeat(12) { index ->
                activity.updateSurface(activity.scene.selected!!.copy(opacity = (index + 1) / 20f))
                activity.saveProject()
            }
        }
        awaitSaved(scenario)
        val id = read(scenario) { it.currentProjectId!! }
        assertEquals(.6f, io { store.load(id).project.state.scene.selected!!.opacity })
    }

    @Test fun saveFailureStaysVisibleKeepsEditsAndRetryPersistsThem() = withApp { scenario ->
        awaitSaved(scenario)
        val id = read(scenario) { it.currentProjectId!! }
        val recovery = File(directory, "$id.recovery")
        io { if (recovery.exists()) check(recovery.delete()); check(recovery.mkdir()); File(recovery, "blocker").writeText("test") }
        try {
            scenario.onActivity { it.updateSurface(it.scene.selected!!.copy(quad = warped)); it.setOutputMode(true); it.saveProject() }
            await(scenario) { it.findViewById<TextView>(R.id.project_status).text.toString().startsWith("Could not save.") }
            assertFalse(read(scenario) { it.outputMode })
            assertEquals(warped, read(scenario) { it.quad })
            SystemClock.sleep(700)
            assertTrue(read(scenario) { it.findViewById<TextView>(R.id.project_status).text.toString().startsWith("Could not save.") })
            scenario.recreate()
            await(scenario) { it.findViewById<TextView>(R.id.project_status).text.toString().startsWith("Could not save.") }
            assertEquals(warped, read(scenario) { it.quad })
        } finally {
            io { check(File(recovery, "blocker").delete()); check(recovery.delete()) }
        }
        text("Retry").click()
        awaitSaved(scenario)
        assertEquals(warped, io { store.load(id).project.state.scene.selected!!.quad })
    }

    @Test fun projectUiCreatesRenamesDuplicatesAndConfirmsDelete() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val name = "Gallery ${System.nanoTime()}"
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            text("New project").click()
            val input = requireNotNull(device.wait(Until.findObject(By.res("com.lumis", "project_name")), 5000))
            text("Create project").click()
            assertNotNull(device.findObject(By.res("com.lumis", "project_name")))
            input.text = name
            text("Create project").click()
            assertNotNull(device.wait(Until.findObject(By.res("com.lumis", "video_canvas")), 8000))
            device.pressBack()
            named("Actions for project $name").click()
            text("Rename project").click()
            requireNotNull(device.wait(Until.findObject(By.res("com.lumis", "project_name")), 5000)).text = "$name renamed"
            text("Save now").click()
            named("Actions for project $name renamed").click()
            text("Duplicate project").click()
            named("Actions for project $name renamed copy").click()
            text("Delete project").click()
            text("Keep project").click()
            named("Open project $name renamed copy")
            named("Actions for project $name renamed copy").click()
            text("Delete project").click()
            text("Delete project").click()
            assertTrue(device.wait(Until.gone(By.desc("Open project $name renamed copy")), 5000))
            named("Open project $name renamed")
        }
    }

    private fun launch(id: String): ActivityScenario<MainActivity> {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
        return ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("project_id", id))
    }

    private fun awaitSaved(scenario: ActivityScenario<MainActivity>) =
        await(scenario) { it.findViewById<TextView>(R.id.project_status).text.toString().endsWith("Saved on device") }

    private fun text(value: String) = requireNotNull(device.wait(Until.findObject(
        By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(value), java.util.regex.Pattern.CASE_INSENSITIVE))), 8000))

    private fun named(value: String) = requireNotNull(device.wait(Until.findObject(By.desc(value)), 8000))

    private fun imageFixture(): Uri {
        val file = File(context.cacheDir, "persistent-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun expectFormatFailure(operation: () -> Unit) {
        try { operation(); fail("Invalid document must fail explicitly") } catch (_: ProjectFormatException) { }
    }

    private fun isolated(block: (ProjectStore, File) -> Unit) {
        val dir = File(context.cacheDir, "store-test-${UUID.randomUUID()}")
        try { block(ProjectStore(dir), dir) } finally {
            dir.listFiles()?.forEach { file -> check(file.delete()) }
            if (dir.exists()) check(dir.delete())
        }
    }

    private fun <T> io(operation: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        ProjectIo.run(operation) { result = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return requireNotNull(result).getOrThrow()
    }

    private val warped = Quad(Point(.19, .13), Point(.83, .2), Point(.88, .86), Point(.12, .78))
}

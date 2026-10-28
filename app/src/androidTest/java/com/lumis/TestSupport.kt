package com.lumis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lumis.geometry.Homography
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import com.lumis.editor.CornerOverlay
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun withApp(block: (ActivityScenario<MainActivity>) -> Unit) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.wakeUp()
    device.executeShellCommand("wm dismiss-keyguard")
    // Android shows a one-time system tutorial over the first immersive app; it is not Lumis output.
    device.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        await(scenario) { it.renderer.videoFrames > 2 && it.findViewById<Button>(R.id.play_pause).isEnabled }
        block(scenario)
        assertEquals("No playback/rendering errors expected", View.GONE, read(scenario) { it.findViewById<View>(R.id.error_panel).visibility })
    }
}

internal fun <T> read(scenario: ActivityScenario<MainActivity>, block: (MainActivity) -> T): T {
    val result = mutableListOf<T>()
    scenario.onActivity { result += block(it) }
    return result.single()
}

internal fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 12_000
    while (SystemClock.uptimeMillis() < deadline) {
        if (read(scenario, condition)) return
        SystemClock.sleep(50)
    }
    fail("Timed out waiting for video or UI state: " + read(scenario) {
        "frames=${it.renderer.videoFrames}, ready=${it.findViewById<Button>(R.id.play_pause).isEnabled}, " +
            "status=${it.findViewById<TextView>(R.id.playback_state).text}, " +
            "canvas=${it.findViewById<View>(R.id.video_canvas).width}x${it.findViewById<View>(R.id.video_canvas).height}"
    })
}

internal fun click(scenario: ActivityScenario<MainActivity>, id: Int) {
    scenario.onActivity { it.findViewById<View>(id).performClick() }
}

internal fun bounds(scenario: ActivityScenario<MainActivity>, id: Int): Rect = read(scenario) {
    val view = it.findViewById<View>(id)
    val position = IntArray(2)
    view.getLocationOnScreen(position)
    Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
}

internal fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val down = SystemClock.uptimeMillis()
    fun inject(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(automation.injectInputEvent(event, true))
        event.recycle()
    }
    inject(MotionEvent.ACTION_DOWN, fromX, fromY)
    for (step in 1..16) {
        SystemClock.sleep(16)
        inject(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / 16, fromY + (toY - fromY) * step / 16)
    }
    inject(MotionEvent.ACTION_UP, toX, toY)
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

internal fun capture(scenario: ActivityScenario<MainActivity>): Bitmap {
    val deadline = SystemClock.uptimeMillis() + 5000
    while (true) {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var result = -1
        scenario.onActivity {
            val view = it.findViewById<GLSurfaceView>(R.id.video_canvas)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap = image
            PixelCopy.request(view, image, { status -> result = status; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val image = requireNotNull(bitmap)
        if (result == PixelCopy.SUCCESS) return image
        image.recycle()
        assertEquals("Only a newly resized, not-yet-buffered surface may be retried", PixelCopy.ERROR_SOURCE_NO_DATA, result)
        assertTrue("GL surface produced no capturable frame within five seconds", SystemClock.uptimeMillis() < deadline)
        scenario.onActivity { it.findViewById<GLSurfaceView>(R.id.video_canvas).requestRender() }
        SystemClock.sleep(50)
    }
}

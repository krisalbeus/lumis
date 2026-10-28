package com.lumis.project

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.lumis.geometry.Quad
import com.lumis.media.MediaSource
import com.lumis.scene.Scene
import com.lumis.scene.SceneSurface
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

data class PlaybackState(val playing: Boolean = true, val position: Int = 0)

data class ProjectState(
    val scene: Scene = Scene(),
    val playback: Map<Long, PlaybackState> = emptyMap(),
    val missing: Map<Long, MediaSource> = emptyMap(),
)

data class Project(
    val id: String,
    val name: String,
    val state: ProjectState,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class LoadedProject(val project: Project, val recovered: Boolean = false)
data class ProjectEntry(val id: String, val name: String, val updatedAt: Long, val problem: Boolean, val surfaceCount: Int)

class ProjectFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Call on ProjectIo's single worker: reads, snapshots, and mutations stay ordered. */
class ProjectStore(private val directory: File) {
    private fun file(id: String): File {
        try {
            require(UUID.fromString(id).toString() == id) { "Invalid project ID" }
        } catch (error: IllegalArgumentException) {
            throw ProjectFormatException("Invalid project identifier", error)
        }
        return File(directory, "$id.json")
    }

    fun create(name: String, state: ProjectState = ProjectState()): Project =
        Project(UUID.randomUUID().toString(), checkedName(name), state).also { save(it) }

    fun list(): List<ProjectEntry> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("Cannot read project directory")
        val ids = files.map { it.name }.filter { it.endsWith(".json") || it.endsWith(".json.bak") }
            .map { it.removeSuffix(".bak").removeSuffix(".json") }.distinct()
        return ids.map { id ->
            try {
                val loaded = load(id)
                ProjectEntry(id, loaded.project.name, loaded.project.updatedAt, loaded.recovered, loaded.project.state.scene.surfaces.size)
            } catch (error: IOException) {
                Log.e("LumisProjects", "Cannot read project $id", error)
                ProjectEntry(id, id, File(directory, "$id.json").lastModified(), true, 0)
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): LoadedProject {
        val primary = file(id)
        try {
            return LoadedProject(decode(AtomicFile(primary).openRead().use { it.readBytes() }, id))
        } catch (error: IOException) {
            val backup = File(directory, "$id.recovery")
            if (!backup.exists()) throw error
            Log.w("LumisProjects", "Trying last known good project snapshot", error)
            return LoadedProject(decode(AtomicFile(backup).openRead().use { it.readBytes() }, id), true)
        }
    }

    fun save(project: Project) {
        val primary = file(project.id)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create project directory")
        val bytes = encode(project).toByteArray(Charsets.UTF_8)
        if (primary.exists()) {
            val previous = AtomicFile(primary).openRead().use { it.readBytes() }
            try {
                decode(previous, project.id)
                write(File(directory, "${project.id}.recovery"), previous)
            } catch (error: ProjectFormatException) {
                // Preserve an existing good recovery snapshot rather than backing up corrupt data.
                Log.w("LumisProjects", "Not replacing recovery with invalid project data", error)
            }
        }
        write(primary, bytes)
    }

    fun rename(id: String, name: String): Project {
        val loaded = load(id)
        if (loaded.recovered) throw ProjectFormatException("Open and recover this project before renaming it")
        return loaded.project.copy(name = checkedName(name), updatedAt = System.currentTimeMillis()).also(::save)
    }

    fun duplicate(id: String): Project {
        val loaded = load(id)
        if (loaded.recovered) throw ProjectFormatException("Open and recover this project before duplicating it")
        val original = loaded.project
        return create("${original.name.take(94)} copy", original.state)
    }

    fun delete(id: String) {
        // A tombstone rename hides the project atomically before deleting its recovery copy.
        val primary = file(id)
        val tombstone = File(directory, "$id.deleted")
        val atomic = AtomicFile(primary)
        if (primary.exists() || File(directory, "$id.json.bak").exists()) {
            atomic.openRead().close()
            if (!primary.renameTo(tombstone)) throw IOException("Cannot remove project")
        }
        val recovery = File(directory, "$id.recovery")
        for (f in listOf(recovery, File(directory, "$id.recovery.bak"), tombstone)) {
            if (f.exists() && !f.delete()) throw IOException("Cannot remove project snapshot")
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.new")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            // Unlike AtomicFile.finishWrite, Os.rename reports publication failures.
            Os.rename(temporary.path, file.path)
            val parent = Os.open(requireNotNull(file.parent), OsConstants.O_RDONLY, 0)
            try { Os.fsync(parent) } finally { Os.close(parent) }
        } catch (error: ErrnoException) {
            throw IOException("Cannot publish project snapshot", error)
        } finally {
            if (temporary.exists() && !temporary.delete()) Log.e("LumisProjects", "Cannot remove incomplete snapshot")
        }
    }

    private fun checkedName(name: String): String {
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "Use a project name of 1-100 characters" }
        return name.trim()
    }

    private fun source(value: MediaSource): JSONObject = JSONObject().apply {
        put("type", when (value) {
            MediaSource.BundledSample -> "sample"
            is MediaSource.Image -> "image"
            is MediaSource.Video -> "video"
        })
        when (value) {
            MediaSource.BundledSample -> Unit
            is MediaSource.Image -> put("uri", value.uri.toString())
            is MediaSource.Video -> put("uri", value.uri.toString())
        }
    }

    private fun source(value: JSONObject): MediaSource = when (value.getString("type")) {
        "sample" -> MediaSource.BundledSample
        "image", "video" -> {
            val uri = Uri.parse(value.getString("uri"))
            require(uri.scheme == "content" || uri.scheme == "file") { "Unsupported media URI" }
            if (value.getString("type") == "image") MediaSource.Image(uri) else MediaSource.Video(uri)
        }
        else -> throw ProjectFormatException("Unknown media source type")
    }

    private fun encode(project: Project): String = JSONObject().apply {
        put("version", 1)
        put("id", project.id)
        put("name", checkedName(project.name))
        put("updatedAt", project.updatedAt)
        put("nextId", project.state.scene.nextId)
        put("selectedId", project.state.scene.selectedId ?: JSONObject.NULL)
        put("surfaces", JSONArray().apply {
            project.state.scene.surfaces.forEach { layer ->
                put(JSONObject().apply {
                    put("id", layer.id)
                    put("source", source(layer.source))
                    put("quad", JSONArray(layer.quad.toCoordinates().toList()))
                    put("visible", layer.visible)
                    put("opacity", layer.opacity)
                    val playback = project.state.playback[layer.id] ?: PlaybackState()
                    require(playback.position >= 0)
                    put("playing", playback.playing)
                    put("position", playback.position)
                    project.state.missing[layer.id]?.let { put("missing", source(it)) }
                })
            }
        })
    }.toString()

    private fun decode(bytes: ByteArray, expectedId: String): Project {
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.getInt("version") != 1) throw ProjectFormatException("Unsupported project version; file was not changed")
            require(json.getString("id") == expectedId) { "Project ID does not match" }
            val playback = mutableMapOf<Long, PlaybackState>()
            val missing = mutableMapOf<Long, MediaSource>()
            val array = json.getJSONArray("surfaces")
            val surfaces = (0 until array.length()).map { index ->
                val layer = array.getJSONObject(index)
                val id = layer.getLong("id")
                val coordinates = layer.getJSONArray("quad")
                require(coordinates.length() == 8)
                val position = layer.getInt("position")
                require(position >= 0)
                playback[id] = PlaybackState(layer.getBoolean("playing"), position)
                if (layer.has("missing")) missing[id] = source(layer.getJSONObject("missing"))
                SceneSurface(id, source(layer.getJSONObject("source")),
                    Quad.fromCoordinates(DoubleArray(8) { coordinates.getDouble(it) }),
                    layer.getBoolean("visible"), layer.getDouble("opacity").toFloat())
            }
            return Project(expectedId, checkedName(json.getString("name")),
                ProjectState(Scene(surfaces, if (json.isNull("selectedId")) null else json.getLong("selectedId"),
                    json.getLong("nextId")), playback, missing), json.getLong("updatedAt"))
        } catch (error: JSONException) {
            throw ProjectFormatException("Project data is incomplete or invalid", error)
        } catch (error: IllegalArgumentException) {
            throw ProjectFormatException("Project contains invalid scene data", error)
        }
    }
}

object ProjectIo {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(operation: () -> T, complete: (Result<T>) -> Unit) {
        worker.execute {
            val result = try {
                Result.success(operation())
            } catch (error: IOException) {
                Log.e("LumisProjects", "Project operation failed", error)
                Result.failure(error)
            }
            main.post { complete(result) }
        }
    }
}

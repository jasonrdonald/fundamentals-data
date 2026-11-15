package com.fundamentalanalysis.app.data

import com.fundamentalanalysis.app.data.remote.DatasetFileSource
import com.fundamentalanalysis.app.data.remote.RemoteDatasetSource
import com.fundamentalanalysis.app.data.remote.SeedDatasetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Locates repository files whether tests run from the app module or the root. */
object DatasetFiles {
    fun file(path: String): File = listOf(File("../$path"), File(path)).firstOrNull { it.exists() }
        ?: error("Missing $path (run tools/derive_parts.py)")

    val seedDir: File get() = file("app/src/main/assets/seed")
    val publishedDir: File get() = file("shared-data/v4")

    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
}

/** Serves files from a directory, counting reads per file. */
open class DirSource(private val dir: File) : DatasetFileSource {
    val reads = mutableMapOf<String, Int>()
    /** Replaces the manifest's bytes (e.g. to publish a newer part version). */
    var manifestOverride: ByteArray? = null
    var error: Exception? = null

    override suspend fun manifest(revalidate: Boolean): ByteArray {
        error?.let { throw it }
        return manifestOverride ?: file("manifest.json")
    }

    override suspend fun file(name: String): ByteArray {
        error?.let { throw it }
        reads[name] = (reads[name] ?: 0) + 1
        return File(dir, name).readBytes()
    }
}

class SeedDir(dir: File = DatasetFiles.seedDir) : DirSource(dir), SeedDatasetSource
class RemoteDir(dir: File = DatasetFiles.seedDir, var configured: Boolean = true) : DirSource(dir), RemoteDatasetSource {
    override val isConfigured: Boolean get() = configured
}

/**
 * The manifest in [dir] with some parts' versions bumped (and optionally a wrong checksum), as
 * a newer publish would look. The part files themselves are unchanged.
 */
fun manifestWith(dir: File, bump: Map<String, Int>, badSha: Set<String> = emptySet(), schema: Int? = null): ByteArray {
    val json = DatasetFiles.json
    val root = json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
    val parts = root.getValue("parts").jsonArray.map { p ->
        val o = p.jsonObject
        val name = (o.getValue("name") as JsonPrimitive).content
        JsonObject(o + buildMap {
            bump[name]?.let { put("version", JsonPrimitive(it)) }
            if (name in badSha) put("sha256", JsonPrimitive("0".repeat(64)))
        })
    }
    val version = (bump.values + listOf((root.getValue("version") as JsonPrimitive).content.toInt())).max()
    return JsonObject(root + mapOf("parts" to JsonArray(parts), "version" to JsonPrimitive(version)) +
        (schema?.let { mapOf("schemaVersion" to JsonPrimitive(it)) } ?: emptyMap())).toString().encodeToByteArray()
}

package com.avnivo.translator

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Proof that a model on disk is the one the manifest describes.
 *
 * A length check is not integrity: a truncated or overwritten file has the wrong digest but can
 * easily keep a plausible size. A digest alone is too slow to repeat on every page load for a
 * 916 MB model, so a successful hash is recorded in a stamp beside the model, keyed by each file's
 * size and mtime. Any file that changes invalidates its own entry and is hashed again.
 */
internal object ModelIntegrity {
    private const val STAMP = ".verified.json"

    /** Outcome of a verification pass, so the caller can report why it failed. */
    internal data class Result(val ok: Boolean, val reason: String? = null, val hashedFiles: Int = 0)

    /**
     * Verifies every file in [spec] under [directory]. Returns ok only when each file exists at the
     * expected length and its SHA-256 matches. Runs on a worker: hashing is CPU- and IO-bound.
     */
    fun verify(directory: File, spec: OfflineModelSpec): Result {
        if (spec.files.isEmpty()) return Result(false, "model fayllari ro‘yxati bo‘sh")
        val stamps = readStamps(directory)
        var hashed = 0
        for ((name, expected) in spec.files) {
            val (size, hash) = expected
            val file = File(directory, name)
            if (!file.isFile) return Result(false, "fayl yo‘q: $name")
            if (file.length() != size) {
                return Result(false, "hajm mos emas: $name (${file.length()} != $size)")
            }
            val key = stampKey(size, file.lastModified())
            if (stamps[name] == key) continue
            val actual = runCatching { sha256Of(file) }.getOrElse {
                return Result(false, "o‘qib bo‘lmadi: $name")
            }
            if (!actual.equals(hash, ignoreCase = true)) {
                return Result(false, "SHA-256 mos emas: $name")
            }
            stamps[name] = key
            hashed++
        }
        writeStamps(directory, stamps)
        return Result(true, hashedFiles = hashed)
    }

    /** True when the stamp already covers every file, so [verify] would only re-stat. */
    fun isStamped(directory: File, spec: OfflineModelSpec): Boolean {
        val stamps = readStamps(directory)
        return spec.files.all { (name, expected) ->
            val file = File(directory, name)
            file.isFile && file.length() == expected.first &&
                stamps[name] == stampKey(expected.first, file.lastModified())
        }
    }

    /** Removes the stamp so the next verification hashes from scratch. */
    fun invalidate(directory: File) {
        runCatching { File(directory, STAMP).delete() }
    }

    private fun stampKey(size: Long, modified: Long): String = "$size:$modified"

    private fun readStamps(directory: File): MutableMap<String, String> {
        val file = File(directory, STAMP)
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) map[key] = json.getString(key)
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun writeStamps(directory: File, stamps: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            for ((key, value) in stamps) json.put(key, value)
            val target = File(directory, STAMP)
            val temporary = File(directory, "$STAMP.tmp")
            temporary.writeText(json.toString())
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

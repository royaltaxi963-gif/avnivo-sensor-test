package com.avnivo.translator

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads only the reviewed Uzbek STT model into app-private storage. Run off the UI thread. */
internal class VerifiedModelDownloader(
    private val directory: File,
    private val apiBase: String = DEFAULT_API_BASE,
    private val allowedHosts: List<String> = DEFAULT_ALLOWED_HOSTS,
    private val modelId: String = MODEL_ID,
    private val expected: Map<String, Pair<Long, String>> = EXPECTED
) {
    companion object {
        const val DEFAULT_API_BASE = "https://avnivo-translation-test-792624108019.europe-west1.run.app"
        val DEFAULT_ALLOWED_HOSTS = listOf(
            "https://storage.googleapis.com/",
            "https://avnivo-offline-models-792624108019.storage.googleapis.com/"
        )
        const val MODEL_ID = "uzbek-stt"
        val EXPECTED = mapOf(
            "model.int8.onnx" to Pair(591649160L, "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"),
            "tokens.txt" to Pair(411L, "2022129a0c61784d4a1838da59e5875f9b29d2c6ad12413cbedc98632f98710f")
        )
    }

    private val ticketUrl get() = "$apiBase/api/offline-models/$modelId/download-ticket"

    fun download(progress: (String) -> Unit, bytesProgress: (Long, Long) -> Unit) {
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create model directory" }
        progress("Model serveriga ulanmoqda…")
        val ticket = JSONObject(readHttps(ticketUrl))
        require(ticket.getString("model_id") == modelId) { "Unexpected model" }
        val files = ticket.getJSONArray("files")
        require(files.length() == expected.size) { "Unexpected file count" }
        val seen = mutableSetOf<String>()
        val staged = mutableListOf<Pair<File, File>>()
        try {
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val name = entry.getString("name")
                val spec = expected[name] ?: error("Unknown model file")
                require(seen.add(name)) { "Duplicate file" }
                require(entry.getLong("bytes") == spec.first &&
                    entry.getString("sha256").equals(spec.second, ignoreCase = true)) { "Manifest mismatch" }
                val url = entry.getString("url")
                require(allowedHosts.any { url.startsWith(it) }) { "Unexpected download host" }
                require(directory.usableSpace > spec.first + 32L * 1024 * 1024) { "Insufficient storage" }
                val temporary = File(directory, "$name.download")
                val destination = File(directory, name)
                progress("Yuklanmoqda: $name")
                downloadFile(url, temporary, spec.first, spec.second, bytesProgress)
                staged.add(temporary to destination)
            }
            require(seen == expected.keys) { "Missing model file" }
            for ((temporary, destination) in staged) {
                require(temporary.renameTo(destination)) { "Cannot install model" }
            }
            progress("Model yuklandi va SHA-256 tekshirildi. Ishga tushirish uchun tayyorlang.")
        } finally {
            for ((temporary, _) in staged) temporary.delete()
            // Keep partial downloads for retry; verified files are only promoted after SHA-256.
        }
    }

    private fun connection(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = false
        return c
    }

    private fun readHttps(url: String): String {
        val c = connection(url)
        return try {
            require(c.responseCode == 200) { "Model serveri HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.use { it.readText().take(240) } ?: "javob yo‘q"}" }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun downloadFile(url: String, file: File, size: Long, hash: String, progress: (Long, Long) -> Unit) {
        val existing = if (file.isFile && file.length() in 1 until size) file.length() else 0L
        val c = connection(url)
        if (existing > 0) c.setRequestProperty("Range", "bytes=$existing-")
        try {
            require(c.responseCode == if (existing > 0) 206 else 200) { "Model fayli HTTP ${c.responseCode}" }
            if (existing > 0) require(c.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true) { "Resume range mismatch" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = existing
            if (existing > 0) file.inputStream().use { old -> val b=ByteArray(65536); while(true){val n=old.read(b);if(n<0)break;digest.update(b,0,n)} }
            progress(total,size)
            c.inputStream.use { input ->
                java.io.FileOutputStream(file, existing > 0).buffered().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > size) { file.delete(); error("File exceeds expected size") }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        progress(total,size)
                    }
                }
            }
            require(total == size) { "Incorrect model file size" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual != hash) { file.delete(); error("SHA-256 mismatch") }
        } finally { c.disconnect() }
    }
}

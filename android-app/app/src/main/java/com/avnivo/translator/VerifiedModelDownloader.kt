package com.avnivo.translator

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads only the reviewed Uzbek STT model into app-private storage. Run off the UI thread. */
internal class VerifiedModelDownloader(private val directory: File) {
    companion object {
        private const val API = "https://avnivo-translation-test-792624108019.europe-west1.run.app/api/offline-models/uzbek-stt/download-ticket"
        private val expected = mapOf(
            "model.int8.onnx" to Pair(591649160L, "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"),
            "tokens.txt" to Pair(411L, "2022129a0c61784d4a1838da59e5875f9b29d2c6ad12413cbedc98632f98710f")
        )
    }

    fun download(progress: (String) -> Unit) {
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create model directory" }
        val ticket = JSONObject(readHttps(API))
        require(ticket.getString("model_id") == "uzbek-stt") { "Unexpected model" }
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
                require(url.startsWith("https://storage.googleapis.com/") ||
                    url.startsWith("https://avnivo-offline-models-792624108019.storage.googleapis.com/")) {
                    "Unexpected download host"
                }
                require(directory.usableSpace > spec.first + 32L * 1024 * 1024) { "Insufficient storage" }
                val temporary = File(directory, "$name.download")
                val destination = File(directory, name)
                progress("Yuklanmoqda: $name")
                downloadFile(url, temporary, spec.first, spec.second)
                staged.add(temporary to destination)
            }
            require(seen == expected.keys) { "Missing model file" }
            for ((temporary, destination) in staged) {
                require(temporary.renameTo(destination)) { "Cannot install model" }
            }
            progress("Model yuklandi va SHA-256 tekshirildi. Ishga tushirish uchun tayyorlang.")
        } finally {
            for ((temporary, _) in staged) temporary.delete()
            for (name in expected.keys) File(directory, "$name.download").delete()
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
            require(c.responseCode == 200) { "Ticket HTTP ${c.responseCode}" }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun downloadFile(url: String, file: File, size: Long, hash: String) {
        val c = connection(url)
        try {
            require(c.responseCode == 200) { "Download HTTP ${c.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= size) { "File exceeds expected size" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            require(total == size) { "Incorrect model file size" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual == hash) { "SHA-256 mismatch" }
        } finally { c.disconnect() }
    }
}

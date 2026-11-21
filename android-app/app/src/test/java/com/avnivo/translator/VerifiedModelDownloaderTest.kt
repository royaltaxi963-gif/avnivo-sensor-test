package com.avnivo.translator

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Exercises the real download pipeline against a real local HTTP server (no network mocks).
 * The model is replaced with tiny fixtures; hashes and sizes are recomputed from the fixtures
 * so verification logic is exercised end to end.
 */
class VerifiedModelDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MiniHttpServer
    private lateinit var baseUrl: String
    private lateinit var allowedHosts: List<String>

    private val modelBytes = ByteArray(5000) { (it * 7 % 251).toByte() }
    private val tokensBytes = "test-token-a\n".toByteArray()

    private val modelSha = sha256(modelBytes)
    private val tokensSha = sha256(tokensBytes)

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expected() = mapOf(
        "model.int8.onnx" to Pair(modelBytes.size.toLong(), modelSha),
        "tokens.txt" to Pair(tokensBytes.size.toLong(), tokensSha)
    )

    private var corruptModel = false
    private var omitTokens = false
    private var forceFullResponse = false

    @Before fun startServer() {
        server = MiniHttpServer { request ->
            when {
                request.path == "/api/offline-models/test-model/download-ticket" -> {
                    val files = JSONArray()
                    if (!omitTokens) files.put(fileEntry("tokens.txt", tokensBytes, tokensSha))
                    files.put(fileEntry("model.int8.onnx", modelBytes, modelSha))
                    val body = JSONObject()
                        .put("model_id", "test-model")
                        .put("expires_in_seconds", 900)
                        .put("files", files)
                        .toString().toByteArray()
                    MiniHttpServer.Response(200, body, mapOf("Content-Type" to "application/json"))
                }
                request.path.startsWith("/files/") -> {
                    val name = request.path.substringAfterLast('/')
                    val payload = when (name) {
                        "model.int8.onnx" -> if (corruptModel) ByteArray(modelBytes.size) { 0x5A } else modelBytes
                        "tokens.txt" -> tokensBytes
                        else -> null
                    }
                    if (payload == null) MiniHttpServer.Response(404, ByteArray(0))
                    else serveWithRange(request, payload)
                }
                else -> MiniHttpServer.Response(404, ByteArray(0))
            }
        }
        server.start()
        baseUrl = server.baseUrl
        allowedHosts = listOf("$baseUrl/files/")
    }

    @After fun stopServer() = server.close()

    private fun fileEntry(name: String, bytes: ByteArray, sha: String): JSONObject = JSONObject()
        .put("name", name)
        .put("bytes", bytes.size)
        .put("sha256", sha)
        .put("url", "$baseUrl/files/$name")

    /** Minimal single-range HTTP server; returns 206 + Content-Range when a Range header is present. */
    private fun serveWithRange(request: MiniHttpServer.Request, payload: ByteArray): MiniHttpServer.Response {
        val range = request.headers["range"]
        if (range == null || forceFullResponse) return MiniHttpServer.Response(200, payload)
        val start = range.removePrefix("bytes=").substringBefore('-').toInt()
        val slice = payload.copyOfRange(start, payload.size)
        return MiniHttpServer.Response(
            206, slice,
            mapOf("Content-Range" to "bytes $start-${payload.size - 1}/${payload.size}", "Accept-Ranges" to "bytes")
        )
    }

    private fun downloader(dir: File, hosts: List<String> = allowedHosts) =
        VerifiedModelDownloader(
            directory = dir,
            apiBase = baseUrl,
            allowedHosts = hosts,
            modelId = "test-model",
            expected = expected()
        )

    @Test fun validDownloadInstallsVerifiedFiles() {
        val dir = temp.newFolder("ok")
        downloader(dir).download({ }, { _, _ -> })
        assertTrue(File(dir, "model.int8.onnx").isFile)
        assertTrue(File(dir, "tokens.txt").isFile)
        assertArrayEquals(modelBytes, File(dir, "model.int8.onnx").readBytes())
        assertArrayEquals(tokensBytes, File(dir, "tokens.txt").readBytes())
        assertFalse(File(dir, "model.int8.onnx.download").exists())
    }

    @Test fun corruptFileIsRejectedAndNotPromoted() {
        corruptModel = true
        val dir = temp.newFolder("corrupt")
        var failed = false
        try {
            downloader(dir).download({ }, { _, _ -> })
        } catch (e: Exception) {
            failed = true
            assertTrue("expected checksum failure, got ${e.message}", e.message!!.contains("SHA-256"))
        }
        assertTrue(failed)
        assertFalse("corrupt model must not be installed", File(dir, "model.int8.onnx").exists())
        assertFalse(File(dir, "model.int8.onnx.download").exists())
    }

    @Test fun missingFileInManifestIsRejected() {
        omitTokens = true
        val dir = temp.newFolder("missing")
        var failed = false
        try {
            downloader(dir).download({ }, { _, _ -> })
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test fun resumedDownloadReusesPartialBytes() {
        val dir = temp.newFolder("resume")
        File(dir, "model.int8.onnx.download").writeBytes(modelBytes.copyOfRange(0, 2000))
        var sawResumeProgress = false
        downloader(dir).download({ }, { done, total ->
            if (done >= 2000 && total == modelBytes.size.toLong()) sawResumeProgress = true
        })
        assertTrue("resume should reuse the partial file", sawResumeProgress)
        assertArrayEquals(modelBytes, File(dir, "model.int8.onnx").readBytes())
    }

    @Test fun serverWithoutRangeSupportFailsResume() {
        val dir = temp.newFolder("norange")
        File(dir, "model.int8.onnx.download").writeBytes(modelBytes.copyOfRange(0, 1000))
        forceFullResponse = true
        var failed = false
        try {
            downloader(dir).download({ }, { _, _ -> })
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("expected failure when server ignores Range", failed)
        assertFalse(File(dir, "model.int8.onnx").exists())
    }

    @Test fun untrustedDownloadHostIsRejected() {
        val dir = temp.newFolder("host")
        var failed = false
        try {
            downloader(dir, hosts = listOf("https://evil.example.com/")).download({ }, { _, _ -> })
        } catch (e: Exception) {
            failed = true
            assertTrue(e.message!!.contains("Unexpected download host"))
        }
        assertTrue(failed)
    }

    @Test fun ticketWithWrongHashIsRejected() {
        val dir = temp.newFolder("badhash")
        val bad = mapOf("model.int8.onnx" to Pair(modelBytes.size.toLong(), "0".repeat(64)))
        var failed = false
        try {
            VerifiedModelDownloader(dir, baseUrl, allowedHosts, "test-model", bad).download({ }, { _, _ -> })
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test fun repeatedDownloadOverwritesAtomically() {
        val dir = temp.newFolder("repeat")
        downloader(dir).download({ }, { _, _ -> })
        val first = File(dir, "model.int8.onnx").readBytes()
        downloader(dir).download({ }, { _, _ -> })
        assertArrayEquals(first, File(dir, "model.int8.onnx").readBytes())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}

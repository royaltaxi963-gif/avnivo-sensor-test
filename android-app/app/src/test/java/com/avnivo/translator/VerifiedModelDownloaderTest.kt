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

    private fun fileEntry(name: String, bytes: ByteArray, sha: String, host: String = baseUrl): JSONObject = JSONObject()
        .put("name", name)
        .put("bytes", bytes.size)
        .put("sha256", sha)
        .put("url", "$host/files/$name")

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
        // Progress is reported across the whole model, so the model file starts after tokens.txt.
        val modelWideTotal = (modelBytes.size + tokensBytes.size).toLong()
        var sawResumeProgress = false
        downloader(dir).download({ }, { done, total ->
            if (done >= 2000L + tokensBytes.size && total == modelWideTotal) sawResumeProgress = true
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

    @Test fun progressAdvancesAcrossEveryFile() {
        // The card draws one bar for the whole model. Reporting per-file totals made an 8-file
        // model restart at 0% eight times, which is what "stuck at 0%" looked like on the device.
        val dir = temp.newFolder("aggregate")
        val total = (modelBytes.size + tokensBytes.size).toLong()
        val seen = mutableListOf<Pair<Long, Long>>()
        downloader(dir).download({ }, { done, reported -> seen += done to reported })

        assertTrue("progress must be reported", seen.isNotEmpty())
        assertTrue(
            "every report must use the model-wide total: $seen",
            seen.all { it.second == total }
        )
        val firsts = seen.map { it.first }
        assertEquals("progress must start at zero", 0L, firsts.first())
        assertTrue(
            "progress must never move backwards: $firsts",
            firsts.zipWithNext().all { (a, b) -> b >= a }
        )
        assertEquals("progress must finish at the total", total, firsts.last())
    }

    @Test fun anAlreadyVerifiedFileIsNotDownloadedAgain() {
        // A resumed install must keep what it already verified instead of refetching every file.
        val dir = temp.newFolder("reuse")
        downloader(dir).download({ }, { _, _ -> })
        val installed = File(dir, "tokens.txt")
        val stamp = installed.lastModified()

        val messages = mutableListOf<String>()
        downloader(dir).download({ messages += it }, { _, _ -> })

        assertTrue(
            "a verified file must be reported as reused: $messages",
            messages.any { it.contains("Allaqachon yuklangan: tokens.txt") }
        )
        assertEquals("a reused file must not be rewritten", stamp, installed.lastModified())
        assertArrayEquals(tokensBytes, installed.readBytes())
    }

    @Test fun aTruncatedInstalledFileIsReplaced() {
        // Reuse must be proven by digest and size, never by the file merely existing.
        val dir = temp.newFolder("truncated")
        downloader(dir).download({ }, { _, _ -> })
        File(dir, "tokens.txt").writeBytes(tokensBytes.copyOfRange(0, 3))

        downloader(dir).download({ }, { _, _ -> })
        assertArrayEquals(tokensBytes, File(dir, "tokens.txt").readBytes())
    }

    @Test fun aFullyVerifiedTemporaryFileIsInstalledWithoutRefetching() {
        // A run that verified a file but died before installing it must not pay for the transfer
        // again. This is the case that made a resumed 916 MB install restart from zero.
        val dir = temp.newFolder("promote")
        downloader(dir).download({ }, { _, _ -> })
        val installed = File(dir, "model.int8.onnx")
        val temporary = File(dir, "model.int8.onnx.download")
        assertTrue("the fixture must move the file aside", installed.renameTo(temporary))

        val messages = mutableListOf<String>()
        downloader(dir).download({ messages += it }, { _, _ -> })

        assertTrue(
            "the verified temp must be promoted: $messages",
            messages.any { it.contains("Tekshirildi, o‘rnatilmoqda: model.int8.onnx") }
        )
        assertArrayEquals(modelBytes, installed.readBytes())
        assertFalse("the temp must not survive installation", temporary.exists())
        assertFalse(
            "the promoted file must not be fetched again: $messages",
            messages.any { it.contains("Yuklanmoqda: model.int8.onnx") }
        )
    }

    @Test fun aCorruptTemporaryFileIsNotPromoted() {
        // Promotion must be gated by the digest, otherwise a partial download could be installed.
        val dir = temp.newFolder("corrupt-temp")
        downloader(dir).download({ }, { _, _ -> })
        val installed = File(dir, "tokens.txt")
        val temporary = File(dir, "tokens.txt.download")
        assertTrue(installed.renameTo(temporary))
        temporary.writeBytes(tokensBytes.copyOfRange(0, 2))

        downloader(dir).download({ }, { _, _ -> })
        assertArrayEquals(tokensBytes, installed.readBytes())
    }

    @Test fun aTransientServerFaultIsRetried() {
        var failures = 0
        // Declared first so the handler can build absolute file URLs for this same server.
        lateinit var flaky: MiniHttpServer
        flaky = MiniHttpServer { request ->
            if (request.path.startsWith("/files/") && failures < 2) {
                failures += 1
                MiniHttpServer.Response(503, ByteArray(0))
            } else if (request.path == "/api/offline-models/test-model/download-ticket") {
                val files = JSONArray()
                    .put(fileEntry("tokens.txt", tokensBytes, tokensSha, flaky.baseUrl))
                    .put(fileEntry("model.int8.onnx", modelBytes, modelSha, flaky.baseUrl))
                MiniHttpServer.Response(
                    200,
                    JSONObject().put("model_id", "test-model").put("files", files).toString().toByteArray()
                )
            } else {
                val payload = when (request.path.substringAfterLast('/')) {
                    "model.int8.onnx" -> modelBytes
                    "tokens.txt" -> tokensBytes
                    else -> null
                }
                if (payload == null) MiniHttpServer.Response(404, ByteArray(0))
                else serveWithRange(request, payload)
            }
        }
        flaky.start()
        try {
            val dir = temp.newFolder("retry")
            VerifiedModelDownloader(
                directory = dir,
                apiBase = flaky.baseUrl,
                allowedHosts = listOf("${flaky.baseUrl}/files/"),
                modelId = "test-model",
                expected = expected()
            ).download({ }, { _, _ -> })
            assertArrayEquals(modelBytes, File(dir, "model.int8.onnx").readBytes())
            assertTrue("the server must have been hit more than once", failures == 2)
        } finally {
            flaky.close()
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}

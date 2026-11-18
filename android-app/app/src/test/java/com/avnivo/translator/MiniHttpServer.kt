package com.avnivo.translator

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal real HTTP/1.1 server on the loopback interface for unit tests.
 *
 * It exists so download tests can exercise genuine sockets, HTTP status codes, Range requests and
 * `Content-Range` handling without pulling in a heavy framework or mocking the network layer.
 */
internal class MiniHttpServer(
    private val handler: (Request) -> Response
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private var running = true

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    data class Request(val path: String, val headers: Map<String, String>)
    data class Response(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    fun start() {
        pool.execute {
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                pool.execute { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input) ?: return
            val path = requestLine.split(" ").getOrElse(1) { "/" }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val response = handler(Request(path, headers))
            val statusText = when (response.status) {
                200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"; else -> "Error"
            }
            val head = StringBuilder("HTTP/1.1 ${response.status} $statusText\r\n")
            response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("Content-Length: ${response.body.size}\r\n")
            head.append("Connection: close\r\n\r\n")
            output.write(head.toString().toByteArray())
            if (response.body.isNotEmpty()) output.write(response.body)
            output.flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var read = input.read()
        if (read == -1) return null
        while (read != -1) {
            if (read == '\n'.code) break
            if (read != '\r'.code) sb.append(read.toChar())
            read = input.read()
        }
        return sb.toString()
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        pool.shutdownNow()
    }
}

package com.avnivo.translator

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * One place for the offline engine diagnostics a physical device test needs.
 *
 * Every line goes to logcat under a stable tag and, when the app has an external files directory,
 * to `diagnostics/offline.log`. That file is what makes a real-device report actionable: the S23
 * Ultra hang could only be described as "stuck", because nothing recorded which native stage was
 * running when it stopped responding.
 *
 * Pull it with:
 *   adb pull /sdcard/Android/data/com.avnivo.translator/files/diagnostics/offline.log
 */
internal object OfflineDiagnostics {
    const val TAG_MODULE = "AvnivoModuleLifecycle"
    const val TAG_STT = "AvnivoOfflineStt"
    const val TAG_WHISPER = "AvnivoWhisper"
    const val TAG_MEMORY = "AvnivoMemory"
    const val TAG_GUARD = "AvnivoGuard"

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val FILE_NAME = "offline.log"

    @Volatile private var logFile: File? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "avnivo-diagnostics").apply { isDaemon = true }
    }

    fun attach(context: Context?) {
        if (context == null) return
        val directory = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return
        runCatching {
            File(directory, "diagnostics").mkdirs()
            logFile = File(directory, "diagnostics/$FILE_NAME")
        }
    }

    fun log(tag: String, message: String) {
        runCatching { android.util.Log.i(tag, message) }
        append(tag, message)
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        if (error == null) runCatching { android.util.Log.e(tag, message) }
        else runCatching { android.util.Log.e(tag, message, error) }
        append(tag, if (error == null) message else "$message | ${error::class.java.simpleName}: ${error.message}")
    }

    private fun append(tag: String, message: String) {
        val file = logFile ?: return
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} " +
            "${Thread.currentThread().name} $tag $message\n"
        runCatching {
            writer.execute {
                runCatching {
                    if (file.length() > MAX_BYTES) {
                        File(file.parentFile, "$FILE_NAME.1").let { old ->
                            old.delete()
                            file.renameTo(old)
                        }
                    }
                    file.appendText(line)
                }
            }
        }
    }
}

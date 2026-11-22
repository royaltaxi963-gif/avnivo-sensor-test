package com.avnivo.translator

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/**
 * What the device can actually give one offline model.
 *
 * [availMem] is the system-wide free memory, and Android deliberately keeps it low by filling
 * unused RAM with page cache. Gating a native allocation on it is wrong twice over: it reports the
 * whole machine rather than this process, and a healthy phone routinely sits near zero. On a
 * Galaxy S23 Ultra with 1768 MB "available" the old gate refused NLLB's 2765 MB forever, even
 * though the phone has 12 GB of physical RAM and nothing else was resident.
 *
 * Native allocations (ONNX Runtime, ggml, LiteRT) live outside the Java heap, so the VM heap
 * ceiling says nothing about them either. The honest budget is a share of physical RAM, refused
 * outright only when the system really is under pressure.
 */
internal data class MemorySnapshot(
    val totalMem: Long,
    val availMem: Long,
    val lowMemory: Boolean,
    val appPssBytes: Long,
    val nativeHeapBytes: Long,
    val vmHeapMax: Long
) {
    /** One model may take at most this share of physical RAM; the rest belongs to the system. */
    val modelBudgetBytes: Long
        get() {
            // Real pressure: the low-memory killer is already active, so no new model fits.
            if (lowMemory || availMem < CRITICAL_FREE_BYTES) return 0L
            val share = (totalMem * MAX_MODEL_SHARE).toLong()
            return share.coerceIn(0L, totalMem / 2)
        }

    /** True when a model needing [requiredBytes] can be loaded without risking a kill. */
    fun fits(requiredBytes: Long): Boolean = requiredBytes <= 0L || requiredBytes <= modelBudgetBytes

    /** One-line diagnosis for the module card and logcat. */
    fun describe(requiredBytes: Long): String =
        "kerak ${mb(requiredBytes)} MB, ruxsat ${mb(modelBudgetBytes)} MB " +
            "(RAM ${mb(totalMem)} MB, bo‘sh ${mb(availMem)} MB, jarayon ${mb(appPssBytes)} MB)"

    companion object {
        /** Reserve the OS needs to keep running; below this the LMK starts killing processes. */
        const val CRITICAL_FREE_BYTES = 384L * 1024 * 1024

        /** A single model may claim at most this fraction of physical RAM. */
        const val MAX_MODEL_SHARE = 0.40

        fun mb(bytes: Long): Long = bytes / (1024L * 1024L)
    }
}

/** Reads the live memory picture from the platform. */
internal object DeviceMemory {
    fun snapshot(context: Context?): MemorySnapshot? {
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val pss = runCatching {
            activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                ?.firstOrNull()?.totalPss?.toLong()?.times(1024L)
        }.getOrNull() ?: 0L
        return MemorySnapshot(
            totalMem = info.totalMem,
            availMem = info.availMem,
            lowMemory = info.lowMemory,
            appPssBytes = pss,
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            vmHeapMax = Runtime.getRuntime().maxMemory()
        )
    }

    /** Diagnostics line for logcat, so a device report carries the real numbers. */
    fun log(context: Context?, tag: String = "AvnivoMemory") {
        val snapshot = snapshot(context) ?: return
        android.util.Log.i(
            tag,
            "MEM total_mb=${MemorySnapshot.mb(snapshot.totalMem)} avail_mb=${MemorySnapshot.mb(snapshot.availMem)} " +
                "pss_mb=${MemorySnapshot.mb(snapshot.appPssBytes)} native_mb=${MemorySnapshot.mb(snapshot.nativeHeapBytes)} " +
                "vm_max_mb=${MemorySnapshot.mb(snapshot.vmHeapMax)} low=${snapshot.lowMemory} " +
                "budget_mb=${MemorySnapshot.mb(snapshot.modelBudgetBytes)}"
        )
    }

    /**
     * Free space needed before a download starts. The check is per file in the downloader; this
     * helper exists so the manager can refuse early with a clear reason instead of failing mid-way.
     */
    fun freeBytes(directory: File): Long = runCatching { directory.usableSpace }.getOrDefault(0L)
}

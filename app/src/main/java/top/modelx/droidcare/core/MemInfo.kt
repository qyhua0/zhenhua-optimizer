package top.modelx.droidcare.core

import java.io.File

/** 读 /proc/meminfo 与 /proc/loadavg(公开可读) */
object MemInfo {
    data class Snapshot(val totalKb: Long, val availKb: Long)

    fun read(): Snapshot? = try {
        val map = HashMap<String, Long>()
        File("/proc/meminfo").forEachLine { line ->
            val p = line.split(Regex("\\s+"))
            if (p.size >= 2) {
                val v = p[1].toLongOrNull()
                if (v != null) map[p[0].removeSuffix(":")] = v
            }
        }
        val t = map["MemTotal"]
        val a = map["MemAvailable"]
        if (t != null && a != null && t > 0) Snapshot(t, a) else null
    } catch (e: Exception) {
        null
    }

    /** 1/5/15 分钟负载, 读不到返回 null */
    fun loadAvg(): Triple<Double, Double, Double>? {
        return try {
            val p = File("/proc/loadavg").readText().trim().split(Regex("\\s+"))
            val a = p[0].toDoubleOrNull() ?: return null
            val b = p[1].toDoubleOrNull() ?: return null
            val c = p[2].toDoubleOrNull() ?: return null
            Triple(a, b, c)
        } catch (e: Exception) {
            null
        }
    }
}

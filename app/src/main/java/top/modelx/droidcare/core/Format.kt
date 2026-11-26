package top.modelx.droidcare.core

import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b < 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) String.format(Locale.US, "%d %s", b, units[i])
        else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun percent(part: Long, total: Long): Int {
        if (total <= 0) return 0
        return ((part * 100) / total).toInt().coerceIn(0, 100)
    }

    fun minutes(m: Long): String = when {
        m >= 60 -> String.format(Locale.US, "%d小时%d分", m / 60, m % 60)
        m > 0 -> String.format(Locale.US, "%d分", m)
        else -> "刚用过"
    }
}

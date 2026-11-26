package top.modelx.droidcare.process

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

data class UsageItem(val pkg: String, val foregroundMs: Long, val lastEventMs: Long)

/**
 * 近 N 小时前台使用情况(需要 PACKAGE_USAGE_STATS 特殊授权)。
 * 无授权时调用方应先检查 Perm.hasUsageAccess。
 */
class UsageRepo(private val ctx: Context) {

    fun recentForeground(hours: Int = 3): List<UsageItem> {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - hours * 3600_000L
            val events = usm.queryEvents(begin, end)
            val fgStart = HashMap<String, Long>()
            val acc = HashMap<String, Long>()
            val last = HashMap<String, Long>()
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                last[pkg] = ev.timeStamp
                when (ev.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> fgStart[pkg] = ev.timeStamp
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val s = fgStart.remove(pkg)
                        if (s != null) {
                            val d = (ev.timeStamp - s).coerceAtLeast(0)
                            acc[pkg] = (acc[pkg] ?: 0) + d
                        }
                    }
                }
            }
            // 仍在前台的补到 now
            for ((pkg, s) in fgStart) acc[pkg] = (acc[pkg] ?: 0) + (end - s).coerceAtLeast(0)
            acc.entries
                .map { UsageItem(it.key, it.value, last[it.key] ?: 0) }
                .sortedByDescending { it.foregroundMs }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

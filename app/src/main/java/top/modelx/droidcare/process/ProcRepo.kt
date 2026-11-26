package top.modelx.droidcare.process

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** 系统限制: 标准 Android 8+ 非 root 只能看到自家进程; 部分厂商 ROM 开放更多, 尽量利用。 */
data class RunningProc(
    val pkg: String,
    val procName: String,
    val pid: Int,
    val importance: Int,   // RunningAppProcessInfo.IMPORTANCE_*
    val rssBytes: Long,    // 读不到=0
) {
    val importanceLabel: String
        get() = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "可感知"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "服务"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "休眠"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "后台"
            else -> "缓存"
        }
}

class ProcRepo(private val ctx: Context) {

    private val am: ActivityManager
        get() = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** 能看到的运行进程, 默认内存(RSS)降序; RSS 全为 0 时按重要性升序(前台在前) */
    @Suppress("DEPRECATION")
    fun runningProcs(): List<RunningProc> {
        val list = try {
            am.runningAppProcesses ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val out = list.map { p ->
            RunningProc(
                pkg = p.processName.substringBefore(':'),
                procName = p.processName,
                pid = p.pid,
                importance = p.importance,
                rssBytes = rssBytes(p.pid),
            )
        }.sortedWith(
            compareByDescending<RunningProc> { it.rssBytes }
                .thenBy { it.importance }
                .thenByDescending { it.pid }
        )
        return out
    }

    private fun rssBytes(pid: Int): Long = try {
        // statm 第二项 = resident pages
        val parts = File("/proc/$pid/statm").readText().trim().split(' ')
        if (parts.size >= 2) parts[1].toLong() * 4096L else 0L
    } catch (e: Exception) {
        0L
    }

    /** 尝试停用/启用应用。第三方应用非 root 通常 SecurityException -> 返回 false 由 UI 引导系统页面 */
    fun trySetEnabled(pkg: String, enabled: Boolean): Boolean {
        return try {
            ctx.packageManager.setApplicationEnabledSetting(
                pkg,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0,
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}

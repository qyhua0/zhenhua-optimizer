package top.modelx.droidcare.shizuku

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import top.modelx.droidcare.process.RunningProc

/**
 * Shizuku 极客模式: 通过 UserService 以 shell(ADB) 身份执行命令。
 * 能力: 全部进程内存(ps/statm)、强制停止(am force-stop)、直接停用/启用(pm disable-user/enable)。
 */
object ShizukuRepo {

    private var app: Context? = null

    @Volatile private var service: IPrivService? = null
    @Volatile private var permResult: Boolean = false

    /** Shizuku 服务是否在运行 */
    val binderAlive: Boolean
        get() = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    /** 用户是否已授权 */
    val permissionGranted: Boolean
        get() = binderAlive && (
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                || permResult)

    val ready: Boolean get() = binderAlive && permissionGranted && service != null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPrivService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun init(application: Application) {
        app = application
        try {
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                permResult = grantResult == PackageManager.PERMISSION_GRANTED
                if (permResult) bindIfNeeded()
            }
            Shizuku.addBinderReceivedListenerSticky { bindIfNeeded() }
            Shizuku.addBinderDeadListener { service = null }
        } catch (t: Throwable) { /* ignore */ }
    }

    fun statusText(): String = when {
        !binderAlive -> "未检测到 Shizuku(未安装或未启动)"
        !permissionGranted -> "Shizuku 运行中, 等待授权"
        ready -> "已就绪(shell uid=${try { service?.uid() } catch (t: Throwable) { "?" }})"
        else -> "已授权, 服务绑定中…"
    }

    fun requestPermission() {
        if (!binderAlive) return
        if (!permissionGranted) {
            try {
                if (!Shizuku.shouldShowRequestPermissionRationale()) {
                    Shizuku.requestPermission(1001)
                }
            } catch (t: Throwable) { /* ignore */ }
        } else {
            bindIfNeeded()
        }
    }

    private fun bindIfNeeded() {
        if (!permissionGranted || service != null) return
        val a = app ?: return
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(a, PrivService::class.java))
                    .daemon(false)
                    .processNameSuffix("priv")
                    .tag("droidcare-priv")
                    .version(1)
                    .debuggable((a.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0),
                conn,
            )
        } catch (t: Throwable) { /* ignore */ }
    }

    fun exec(cmd: String): String? = try {
        service?.exec(cmd)
    } catch (t: Throwable) {
        null
    }

    /** 真正强制停止应用(adb 级) */
    fun forceStop(pkg: String): Boolean {
        val safe = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        val out = exec("am force-stop $safe") ?: return false
        return !out.startsWith("ERR") && !out.contains("SecurityException")
    }

    /** 真正停用/启用应用(adb 级)。pm 成功会输出 "new state: xxx" */
    fun setEnabled(pkg: String, enabled: Boolean): Boolean {
        val safe = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        val cmd = if (enabled) "pm enable $safe" else "pm disable-user --user 0 $safe"
        val out = exec(cmd) ?: return false
        return !out.startsWith("ERR") && (out.contains("new state") || out.contains("Success"))
    }

    /**
     * 全部应用进程 + 真实内存(shell 身份可读 /proc)。
     * impMap: 已知 pid -> importance(来自 ActivityManager), 未知按 300(服务/后台)处理。
     */
    fun procs(impMap: Map<Int, Int>): List<RunningProc> {
        val rssOut = exec(
            """awk 'FNR==1{p=FILENAME; gsub("/proc/","",p); gsub("/statm","",p); print p, $2}' /proc/[0-9]*/statm""",
        ) ?: return emptyList()
        val rss = HashMap<Int, Long>()
        for (line in rssOut.lineSequence()) {
            val t = line.trim().split(' ')
            if (t.size >= 2) {
                val pid = t[0].toIntOrNull() ?: continue
                val pages = t[1].toLongOrNull() ?: continue
                rss[pid] = pages * 4096L
            }
        }
        val psOut = exec("ps -A -o PID= -o NAME=") ?: return emptyList()
        val out = ArrayList<RunningProc>()
        for (line in psOut.lineSequence()) {
            val t = line.trim().split(Regex("\\s+"), limit = 2)
            if (t.size < 2) continue
            val pid = t[0].toIntOrNull() ?: continue
            val name = t[1]
            if (name.contains('/')) continue // 跳过 native 进程, 只列应用进程
            out.add(
                RunningProc(
                    pkg = name.substringBefore(':'),
                    procName = name,
                    pid = pid,
                    importance = impMap[pid] ?: 300,
                    rssBytes = rss[pid] ?: 0L,
                ),
            )
        }
        return out.sortedWith(
            compareByDescending<RunningProc> { it.rssBytes }
                .thenBy { it.importance }
                .thenByDescending { it.pid },
        )
    }
}

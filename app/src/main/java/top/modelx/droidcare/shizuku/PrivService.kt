package top.modelx.droidcare.shizuku

import android.os.Process

/**
 * Shizuku UserService: 本类由 Shizuku 反射实例化在独立进程中,
 * 以 shell(uid=2000, ADB 激活时) 身份运行, 因此 Runtime.exec 拥有 adb shell 权限。
 * 不是 Android Service, 不要继承 Service。
 */
@Suppress("unused", "UNUSED_PARAMETER")
class PrivService : IPrivService.Stub {

    constructor()

    /** 允许带 Context 的构造(Shizuku 13+ 会优先尝试) */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: android.content.Context?)

    override fun exec(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.readBytes()
            val err = p.errorStream.readBytes()
            p.waitFor()
            val e = if (err.isNotEmpty()) "\nSTDERR:" + String(err) else ""
            String(out) + e
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    override fun uid(): Int = Process.myUid()

    /** 供 daemon 模式清理(当前 daemon=false, 保留) */
    fun destroy() {
        try { System.exit(0) } catch (t: Throwable) { /* ignore */ }
    }
}

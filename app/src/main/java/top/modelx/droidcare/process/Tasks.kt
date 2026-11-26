package top.modelx.droidcare.process

/** 任务管理行: 进程 + 资源占用 + 元信息(是否系统应用/有网络权限/疑似广告) */
data class TaskInfo(
    val pid: Int,
    val pkg: String,
    val procName: String,
    val user: String,          // ps 的 USER 列, 如 u0_a123 / system / root
    val rssBytes: Long,
    val cpuPct: Double,        // -1 = 无数据(首轮或标准模式)
    val txBytes: Long,         // 开机以来上行(该 uid 合计), -1=无数据
    val rxBytes: Long,
    val importance: Int,
    val systemApp: Boolean,
    val hasInternet: Boolean,
    val suspicious: Boolean,
    val suspended: Boolean,    // 应用处于停用状态
) {
    val importanceLabel: String
        get() = when (importance) {
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "可感知"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "服务"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "休眠"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "后台"
            else -> "缓存"
        }

    companion object {
        /** 常见 OEM 广告/推荐/推送/浏览器系预装包名关键词(仅辅助标记, 最终以用户判断为准) */
        private val SUSPECT_KEYS = listOf(
            "ads", "adv", "advert", "recommend", "msa", "mzsystem", "getapps",
            "omri", "push", "hians", "hiad", "mhr", "deepthink", "mipush",
            "browser", "search", "msf", "update", "appcloud", "theme",
        )

        fun suspect(pkg: String, label: String): Boolean {
            val p = pkg.lowercase()
            if (SUSPECT_KEYS.any { p.contains(it) }) return true
            val l = label.lowercase()
            return l.contains("广告") || l.contains("推荐") || l.contains("推送") ||
                l.contains("上报") || l.contains("遥测")
        }
    }
}

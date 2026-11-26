package top.modelx.droidcare.process

import android.os.Build

/**
 * 系统引导模式: 无 root/Shizuku 时, 「结束进程」= 跳转到系统页面由用户强行停止。
 * 厂商 ROM 按钮位置不同, 给出行指文案。
 */
object StopGuide {

    fun vendorHint(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("blackshark") ->
                "进入页面 → 点「强行停止」→ 确认。禁止自启: 设置 → 应用设置 → 应用管理 → 省电策略 → 无限制的反面。"
            m.contains("huawei") || m.contains("honor") ->
                "进入页面 → 点「强行停止」→ 确认。自启动管理请去「手机管家 → 应用启动管理」。"
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ->
                "进入页面 → 点「强行停止」→ 确认。后台限制: 设置 → 应用 → 应用管理 → 该应用 → 耗电管理。"
            m.contains("vivo") || m.contains("iqoo") ->
                "进入页面 → 点「强行停止」→ 确认。后台高耗电: i管家 → 应用管理 → 后台高耗电。"
            m.contains("samsung") ->
                "进入页面 → 点「停止」→ 确认。后台限制: 设置 → 电池 → 后台使用限制。"
            else ->
                "进入应用信息页 → 点「强行停止」→ 确认。"
        }
    }

    const val WARNING = "强行停止会立即结束该应用, 未保存的数据可能丢失; 系统核心应用无法停止。DroidCare 不会自动杀后台。"
}

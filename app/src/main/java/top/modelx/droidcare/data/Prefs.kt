package top.modelx.droidcare.data

import android.content.Context
import android.os.Environment

class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("droidcare", Context.MODE_PRIVATE)

    /** 用户自定义白名单前缀 */
    fun userWhitelist(): Set<String> = sp.getStringSet("whitelist", emptySet()) ?: emptySet()

    fun addUserWhitelist(prefix: String) {
        val s = userWhitelist().toMutableSet()
        s.add(prefix.trimEnd('/'))
        sp.edit().putStringSet("whitelist", s).apply()
    }

    fun removeUserWhitelist(prefix: String) {
        val s = userWhitelist().toMutableSet()
        s.remove(prefix)
        sp.edit().putStringSet("whitelist", s).apply()
    }

    /** 完整白名单 = 默认 + 用户 */
    fun whitelist(ownPkg: String): List<String> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val defaults = listOf(
            "$root/DCIM",
            "$root/Android/media/com.whatsapp",
            "$root/Android/data/com.tencent.mm/MicroMsg",
            "$root/Android/media/com.tencent.mm",
            "$root/Android/data/$ownPkg",
            "$root/Android/media/$ownPkg",
        )
        return defaults + userWhitelist()
    }

    var lastCleanFreed: Long
        get() = sp.getLong("last_clean_freed", 0L)
        set(v) { sp.edit().putLong("last_clean_freed", v).apply() }

    var lastCleanAt: Long
        get() = sp.getLong("last_clean_at", 0L)
        set(v) { sp.edit().putLong("last_clean_at", v).apply() }

    companion object {
        const val LARGE_FILE_MB = 100L
    }
}

package top.modelx.droidcare.engine

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class Kind { DIR_NAME, PATH_CONTAINS, EXTENSION }
enum class Risk { SAFE, CAUTION, DANGER }

data class JunkRule(
    val id: String,
    val kind: Kind,
    val match: String,
    val desc: String,
    val risk: Risk,
    val defaultChecked: Boolean,
)

data class JunkHit(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val ruleId: String,
    val category: String,
    val risk: Risk,
    val defaultChecked: Boolean,
    val mtime: Long,
)

sealed interface ScanEvent {
    data class Progress(val files: Int, val current: String, val found: Long) : ScanEvent
    data class HitFound(val hit: JunkHit) : ScanEvent
    data class Done(val totalFound: Long, val elapsedMs: Long) : ScanEvent
}

object RuleStore {
    fun load(ctx: Context): List<JunkRule> {
        return try {
            val text = ctx.assets.open("junk_rules.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("rules")
            val list = ArrayList<JunkRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    JunkRule(
                        id = o.getString("id"),
                        kind = Kind.valueOf(o.getString("kind")),
                        match = o.getString("match"),
                        desc = o.getString("desc"),
                        risk = Risk.valueOf(o.getString("risk")),
                        defaultChecked = o.optBoolean("checked", false),
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package top.modelx.droidcare.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 软删除回收站: 清理 = 移动到应用私有 trash + 记录元数据, 可还原, 7 天后真删。
 * 跨分区 rename 失败时, 文件走复制+删除, 目录尽力而为(失败记 failed)。
 */
class TrashManager(ctx: Context) {

    data class Entry(
        val id: String,
        val origPath: String,
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val deletedAt: Long,
    )

    private val trashDir = File(ctx.filesDir, "trash").apply { mkdirs() }
    private val metaFile = File(trashDir, "meta.json")

    fun list(): List<Entry> {
        return try {
            if (!metaFile.exists()) return emptyList()
            val arr = JSONArray(metaFile.readText())
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(
                    id = o.getString("id"),
                    origPath = o.getString("orig"),
                    name = o.getString("name"),
                    size = o.getLong("size"),
                    isDir = o.getBoolean("dir"),
                    deletedAt = o.getLong("at"),
                )
                // 文件已被外部清掉则丢弃记录
                if (target(e).exists()) out.add(e)
            }
            out.sortedByDescending { it.deletedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun moveToTrash(orig: File): Boolean {
        if (!orig.exists()) return false
        val isDir = orig.isDirectory
        val size = if (isDir) dirSizeSlow(orig) else orig.length()
        val id = UUID.randomUUID().toString().take(12)
        val dst = File(trashDir, id)
        val ok = if (orig.renameTo(dst)) {
            true
        } else if (!isDir) {
            try {
                orig.copyTo(dst, overwrite = true)
                orig.delete() || dst.exists()
            } catch (e: Exception) {
                dst.delete()
                false
            }
        } else {
            try {
                copyDir(orig, dst) && deleteRecursively(orig)
            } catch (e: Exception) {
                deleteRecursively(dst)
                false
            }
        }
        if (!ok) return false
        appendMeta(Entry(id, orig.absolutePath, orig.name, size, isDir, System.currentTimeMillis()))
        return true
    }

    fun restore(e: Entry): Boolean {
        val src = target(e)
        if (!src.exists()) return false
        val orig = File(e.origPath)
        if (orig.exists()) return false
        val ok = src.renameTo(orig) || (if (!e.isDir) {
            try { src.copyTo(orig, overwrite = true); src.delete(); true } catch (x: Exception) { false }
        } else {
            try { copyDir(src, orig) && deleteRecursively(src) } catch (x: Exception) { false }
        })
        if (ok) removeMeta(e.id)
        return ok
    }

    fun deleteForever(e: Entry): Boolean {
        val ok = deleteRecursively(target(e))
        if (ok) removeMeta(e.id)
        return ok
    }

    fun purgeExpired(days: Long): Int {
        val deadline = System.currentTimeMillis() - days * 24 * 3600_000
        var n = 0
        for (e in list()) {
            if (e.deletedAt < deadline && deleteForever(e)) n++
        }
        return n
    }

    private fun target(e: Entry): File = File(trashDir, e.id)

    private fun appendMeta(e: Entry) {
        try {
            val arr = if (metaFile.exists()) JSONArray(metaFile.readText()) else JSONArray()
            val o = JSONObject()
            o.put("id", e.id); o.put("orig", e.origPath); o.put("name", e.name)
            o.put("size", e.size); o.put("dir", e.isDir); o.put("at", e.deletedAt)
            arr.put(o)
            metaFile.writeText(arr.toString())
        } catch (ex: Exception) { /* ignore */ }
    }

    private fun removeMeta(id: String) {
        try {
            if (!metaFile.exists()) return
            val arr = JSONArray(metaFile.readText())
            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("id") != id) keep.put(o)
            }
            metaFile.writeText(keep.toString())
        } catch (ex: Exception) { /* ignore */ }
    }

    private fun dirSizeSlow(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cs = stack.removeLast().listFiles() ?: continue
            for (c in cs) {
                if (c.isDirectory) stack.addLast(c) else total += c.length()
            }
        }
        return total
    }

    private fun copyDir(src: File, dst: File): Boolean {
        dst.mkdirs()
        val cs = src.listFiles() ?: return false
        for (c in cs) {
            val d = File(dst, c.name)
            if (c.isDirectory) { if (!copyDir(c, d)) return false }
            else { c.copyTo(d, overwrite = true) }
        }
        return true
    }

    private fun deleteRecursively(f: File): Boolean {
        if (!f.exists()) return true
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursively(it) }
        }
        return f.delete()
    }
}

package top.modelx.droidcare.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import top.modelx.droidcare.engine.DiskScanner
import java.io.File
import java.util.UUID

data class VolumeInfo(val label: String, val total: Long, val free: Long, val primary: Boolean)

data class DirEntry(val path: String, val name: String, val isDir: Boolean, val size: Long)

class StorageRepo(private val ctx: Context) {

    fun volumes(): List<VolumeInfo> {
        val out = ArrayList<VolumeInfo>()
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            @Suppress("DEPRECATION")
            for (v in sm.storageVolumes) {
                val f = v.directory ?: continue
                val st = StatFs(f.absolutePath)
                out.add(VolumeInfo(
                    v.getDescription(ctx) ?: "存储",
                    st.totalBytes, st.availableBytes, v.isPrimary))
            }
        } catch (e: Exception) { /* fallback below */ }
        if (out.isEmpty()) {
            val root = Environment.getExternalStorageDirectory()
            val st = StatFs(root.absolutePath)
            out.add(VolumeInfo("内部存储", st.totalBytes, st.availableBytes, true))
        }
        return out
    }

    data class Category(val name: String, val bytes: Long)

    /**
     * 系统卷分类统计。queryStatsForVolume 走反射(部分编译环境 android.jar 缺失该方法),
     * 真机 API 26+ 均可用; 失败返回空列表。
     */
    fun volumeCategories(): List<Category> {
        return try {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            @Suppress("DEPRECATION")
            val uuidStr = sm.storageVolumes.firstOrNull { it.isPrimary }?.uuid ?: return emptyList()
            val uuid = UUID.fromString(uuidStr)
            val method = ssm.javaClass.getMethod(
                "queryStatsForVolume", UUID::class.java)
            val s = method.invoke(ssm, uuid) ?: return emptyList()
            fun get(name: String): Long = try {
                s.javaClass.getMethod(name).invoke(s) as Long
            } catch (e: Exception) { 0L }
            listOf(
                Category("应用", get("getAppBytes")),
                Category("系统", get("getSystemBytes")),
                Category("图片", get("getImagesBytes")),
                Category("视频", get("getVideoBytes")),
                Category("音频", get("getAudioBytes")),
                Category("文档", get("getDocumentBytes")),
                Category("下载", get("getDownloadBytes")),
                Category("其他", get("getRootBytes")),
            ).filter { it.bytes > 0 }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 目录钻取: 子目录大小 + 文件, 合并降序取前 limit */
    fun loadDir(path: String, limit: Int = 300): List<DirEntry> {
        val dir = File(path)
        val children = dir.listFiles() ?: return emptyList()
        val entries = ArrayList<DirEntry>(children.size)
        for (c in children) {
            val size = if (c.isDirectory) DiskScanner.dirSize(c) else c.length()
            entries.add(DirEntry(c.absolutePath, c.name, c.isDirectory, size))
        }
        entries.sortByDescending { it.size }
        return entries.take(limit)
    }

    /** 全盘大文件 Top N */
    fun largeFiles(root: File, threshold: Long, topN: Int = 100): List<DirEntry> {
        val out = ArrayList<DirEntry>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val cs = stack.removeLast().listFiles() ?: continue
            for (c in cs) {
                if (c.isDirectory) stack.addLast(c)
                else if (c.length() >= threshold) {
                    out.add(DirEntry(c.absolutePath, c.name, false, c.length()))
                    if (out.size > topN * 4) {
                        out.sortByDescending { it.size }
                        while (out.size > topN) out.removeAt(out.size - 1)
                    }
                }
            }
        }
        out.sortByDescending { it.size }
        return out.take(topN)
    }
}

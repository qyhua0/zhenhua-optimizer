package top.modelx.droidcare.app

import android.os.storage.StorageManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import top.modelx.droidcare.core.Perm
import java.io.File

data class AppItem(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val installBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val installedAt: Long,
    val targetSdk: Int,
    val versionName: String,
) {
    val totalBytes: Long get() = installBytes + dataBytes + cacheBytes
}

class AppRepo(private val ctx: Context) {

    private val pm: PackageManager get() = ctx.packageManager

    fun load(withStats: Boolean): List<AppItem> {
        // 0x400 = MATCH_DISABLED_PACKAGES(@hide): 让已停用应用也出现在列表里
        val pkgs: List<PackageInfo> = try {
            pm.getInstalledPackages(0x400)
        } catch (e: Exception) {
            pm.getInstalledPackages(0)
        }
        val out = ArrayList<AppItem>(pkgs.size)
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val flags = ai.flags
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            var inst = 0L; var data = 0L; var cache = 0L
            if (withStats && Perm.hasUsageAccess(ctx)) {
                try {
                    val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE)
                        as android.app.usage.StorageStatsManager
                    val uuid = primaryUuid()
                    if (uuid != null) {
                        val s = ssm.queryStatsForPackage(uuid, pi.packageName, Process.myUserHandle())
                        inst = s.appBytes
                        data = s.dataBytes
                        cache = s.cacheBytes
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            out.add(AppItem(
                pkg = pi.packageName,
                label = pm.getApplicationLabel(ai).toString(),
                isSystem = isSystem,
                enabled = ai.enabled,
                installBytes = inst,
                dataBytes = data,
                cacheBytes = cache,
                installedAt = pi.firstInstallTime,
                targetSdk = ai.targetSdkVersion,
                versionName = pi.versionName ?: "",
            ))
        }
        return out
    }

    private fun primaryUuid(): java.util.UUID? {
        return try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            @Suppress("DEPRECATION")
            val uuid = sm.storageVolumes.firstOrNull { it.isPrimary }?.uuid
            if (uuid != null) java.util.UUID.fromString(uuid) else null
        } catch (e: Exception) {
            null
        }
    }

    /** 卸载残留: Android/data|media|obb 下不存在对应已装包的目录 */
    fun orphanDirs(): List<Pair<String, Long>> {
        val root = Environment.getExternalStorageDirectory()
        val androidDir = File(root, "Android")
        val result = ArrayList<Pair<String, Long>>()
        val installed = pm.getInstalledPackages(0).map { it.packageName }.toHashSet()
        for (sub in listOf("data", "media", "obb")) {
            val d = File(androidDir, sub)
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory && c.name !in installed && c.name != ctx.packageName) {
                    result.add(c.absolutePath to top.modelx.droidcare.engine.DiskScanner.dirSize(c))
                }
            }
        }
        return result
    }
}

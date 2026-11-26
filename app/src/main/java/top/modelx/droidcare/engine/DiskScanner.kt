package top.modelx.droidcare.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * 磁盘垃圾扫描引擎: 迭代遍历 + 规则命中 + 进度流。
 * 取消协程即可中断扫描。
 */
class DiskScanner(
    private val rules: List<JunkRule>,
    private val whitelist: List<String>,
    private val largeThreshold: Long,
) {

    fun scan(root: File): Flow<ScanEvent> = flow {
        val t0 = System.currentTimeMillis()
        var files = 0
        var found = 0L
        val dirRules = rules.filter { it.kind == Kind.DIR_NAME }
        val pathRules = rules.filter { it.kind == Kind.PATH_CONTAINS }
        val extRules = rules.filter { it.kind == Kind.EXTENSION }

        val stack = ArrayDeque<File>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val d = stack.removeLast()
            val children = try { d.listFiles() } catch (e: Exception) { null } ?: continue
            var realChildren = 0
            for (c in children) {
                files++
                val path = c.absolutePath
                if (isWhitelisted(path)) continue
                if (c.isDirectory) {
                    realChildren++
                    val nameLc = c.name.lowercase()
                    val hitRule = dirRules.firstOrNull { it.match == nameLc }
                        ?: pathRules.firstOrNull { path.lowercase().contains(it.match) }
                    if (hitRule != null) {
                        val sz = dirSize(c)
                        found += sz
                        emit(ScanEvent.HitFound(JunkHit(
                            path, true, sz, hitRule.id, hitRule.desc,
                            hitRule.risk, hitRule.defaultChecked, c.lastModified())))
                    } else {
                        stack.addLast(c)
                    }
                } else {
                    val ext = c.extension.lowercase()
                    val re = extRules.firstOrNull { it.match == ext }
                    val len = c.length()
                    if (re != null) {
                        found += len
                        emit(ScanEvent.HitFound(JunkHit(
                            path, false, len, re.id, re.desc,
                            re.risk, re.defaultChecked, c.lastModified())))
                    } else if (len >= largeThreshold) {
                        found += len
                        emit(ScanEvent.HitFound(JunkHit(
                            path, false, len, "big",
                            "大文件(≥" + (largeThreshold / (1024 * 1024)) + "MB)",
                            Risk.CAUTION, false, c.lastModified())))
                    }
                }
                currentCoroutineContext().ensureActive()
            }
            // 空目录(非根、非隐藏)
            if (realChildren == 0 && d != root && !d.name.startsWith(".")) {
                emit(ScanEvent.HitFound(JunkHit(
                    d.absolutePath, true, 0, "empty_dir", "空目录",
                    Risk.SAFE, true, d.lastModified())))
            }
            if (files % 200 == 0) {
                emit(ScanEvent.Progress(files, d.absolutePath, found))
            }
        }
        emit(ScanEvent.Done(found, System.currentTimeMillis() - t0))
    }.flowOn(Dispatchers.IO)

    private fun isWhitelisted(path: String): Boolean {
        for (w in whitelist) {
            if (w.isNotEmpty() && path.startsWith(w)) return true
        }
        return false
    }

    companion object {
        fun dirSize(dir: File): Long {
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val d = stack.removeLast()
                val cs = try { d.listFiles() } catch (e: Exception) { null } ?: continue
                for (c in cs) {
                    if (c.isDirectory) stack.addLast(c) else total += c.length()
                }
            }
            return total
        }
    }
}

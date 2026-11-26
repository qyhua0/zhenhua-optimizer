package top.modelx.droidcare.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.modelx.droidcare.core.Format
import top.modelx.droidcare.core.Perm
import top.modelx.droidcare.data.Prefs
import top.modelx.droidcare.data.TrashManager
import top.modelx.droidcare.engine.*
import java.io.File

sealed interface CleanUi {
    object Idle : CleanUi
    data class Scanning(val files: Int, val current: String, val found: Long) : CleanUi
    data class Results(val items: List<JunkHit>, val checked: Set<String>) : CleanUi
    data class Working(val done: Int, val total: Int) : CleanUi
    data class Freed(val freed: Long, val failed: Int, val moved: Int) : CleanUi
}

class CleanViewModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf<CleanUi>(CleanUi.Idle)
        private set

    private var scanJob: Job? = null
    private val hits = ArrayList<JunkHit>()

    fun startScan() {
        if (ui is CleanUi.Scanning) return
        hits.clear()
        val ctx = getApplication<Application>()
        val rules = RuleStore.load(ctx)
        val prefs = Prefs(ctx)
        val scanner = DiskScanner(rules, prefs.whitelist(ctx.packageName),
            Prefs.LARGE_FILE_MB * 1024 * 1024)
        val root = Environment.getExternalStorageDirectory()
        scanJob = viewModelScope.launch {
            ui = CleanUi.Scanning(0, "", 0)
            try {
                scanner.scan(root).collect { ev ->
                    when (ev) {
                        is ScanEvent.HitFound -> hits.add(ev.hit)
                        is ScanEvent.Progress ->
                            ui = CleanUi.Scanning(ev.files, ev.current, ev.found)
                        is ScanEvent.Done -> {
                            val defaultChecked = hits.filter { it.defaultChecked }
                                .map { it.path }.toSet()
                            ui = CleanUi.Results(hits.toList(), defaultChecked)
                        }
                    }
                }
            } catch (e: Exception) {
                ui = CleanUi.Idle
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        ui = CleanUi.Idle
    }

    fun reset() {
        ui = CleanUi.Idle
    }

    fun toggle(path: String) {
        val r = ui as? CleanUi.Results ?: return
        val checked = r.checked.toMutableSet()
        if (!checked.remove(path)) checked.add(path)
        ui = r.copy(checked = checked)
    }

    fun selectAll(on: Boolean) {
        val r = ui as? CleanUi.Results ?: return
        ui = r.copy(checked = if (on) r.items.map { it.path }.toSet() else emptySet())
    }

    fun toggleAll(path: String, on: Boolean) {
        val r = ui as? CleanUi.Results ?: return
        val checked = r.checked.toMutableSet()
        r.items.filter { it.category == path }.forEach {
            if (on) checked.add(it.path) else checked.remove(it.path)
        }
        ui = r.copy(checked = checked)
    }

    fun clean() {
        val r = ui as? CleanUi.Results ?: return
        val targets = r.items.filter { it.path in r.checked }
            .sortedByDescending { it.path.length }
        if (targets.isEmpty()) { ui = CleanUi.Freed(0, 0, 0); return }
        val ctx = getApplication<Application>()
        val trash = TrashManager(ctx)
        val prefs = Prefs(ctx)
        viewModelScope.launch {
            var freed = 0L
            var failed = 0
            var done = 0
            ui = CleanUi.Working(0, targets.size)
            for (h in targets) {
                val ok = trash.moveToTrash(File(h.path))
                if (ok) freed += h.size else failed++
                done++
                ui = CleanUi.Working(done, targets.size)
            }
            prefs.lastCleanFreed = freed
            prefs.lastCleanAt = System.currentTimeMillis()
            ui = CleanUi.Freed(freed, failed, targets.size - failed)
        }
    }
}

@Composable
fun CleanScreen(open: (String) -> Unit = {}, vm: CleanViewModel = viewModel()) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("垃圾清理", "扫描缓存/临时/残留文件 · 清理=移入回收站(7天可还原)")
        if (Build.VERSION.SDK_INT >= 30 && !Perm.hasAllFileAccess()) {
            SectionCard {
                Text("需要「所有文件访问」权限才能扫描公共目录",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    ctx.startActivity(Perm.manageStorageIntent()
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("去授权") }
            }
        }
        when (val s = vm.ui) {
            is CleanUi.Idle -> SectionCard {
                Button(onClick = { vm.startScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text("开始扫描")
                }
            }
            is CleanUi.Scanning -> SectionCard {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(String.format("已扫描 %d 个文件 · 发现 %s", s.files, Format.bytes(s.found)))
                Text(s.current, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.cancelScan() }) { Text("取消") }
            }
            is CleanUi.Results -> CleanResults(s, vm, Modifier.weight(1f))
            is CleanUi.Working -> SectionCard {
                LinearProgressIndicator(
                    progress = { s.done.toFloat() / s.total.toFloat() },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(String.format("清理中 %d/%d …", s.done, s.total))
            }
            is CleanUi.Freed -> FreedSummary(s, vm, open, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FreedSummary(
    s: CleanUi.Freed,
    vm: CleanViewModel,
    open: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(88.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxSize(),
            ) {}
            Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("清理完成", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            Format.bytes(s.freed),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (s.moved > 0) "${s.moved} 个项目已移入回收站 · 7 天内可还原" else "未选择任何项目",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.failed > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${s.failed} 个项目清理失败(可能被系统或其他应用占用)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE65100),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.reset() }) { Text("再扫一次") }
            if (s.moved > 0) {
                Button(onClick = { open("trash") }) { Text("查看回收站") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CleanResults(s: CleanUi.Results, vm: CleanViewModel, modifier: Modifier = Modifier) {
    val grouped = remember(s.items) {
        s.items.groupBy { it.category }
            .toList()
            .sortedByDescending { (_, l) -> l.sumOf { it.size } }
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val totalChecked = s.items.filter { it.path in s.checked }.sumOf { it.size }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "summary") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(String.format("共 %d 类 · %d 项", grouped.size, s.items.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.selectAll(true) }) { Text("全选") }
                TextButton(onClick = { vm.selectAll(false) }) { Text("清空") }
            }
        }
        for ((cat, list) in grouped) {
            val catSize = list.filter { it.path in s.checked }.sumOf { it.size }
            val isOpen = expanded[cat] ?: (list.size <= 8)
            item(key = "h_$cat") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { expanded[cat] = !isOpen }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = list.all { it.path in s.checked },
                            onCheckedChange = { on -> vm.toggleAll(cat, on) },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat, style = MaterialTheme.typography.titleSmall)
                            Text(String.format("%d 项 · %s%s", list.size, Format.bytes(catSize),
                                if (isOpen) "" else " · 点行展开"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (isOpen) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isOpen) items(list, key = { it.path }) { hit ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.toggle(hit.path) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = hit.path in s.checked, onCheckedChange = null,
                        modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.path.substringAfterLast('/'), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(hit.path, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    val riskColor = when (hit.risk) {
                        Risk.SAFE -> MaterialTheme.colorScheme.primary
                        Risk.CAUTION -> Color(0xFFE65100)
                        Risk.DANGER -> Color(0xFFC62828)
                    }
                    Text(Format.bytes(hit.size), style = MaterialTheme.typography.bodySmall,
                        color = riskColor)
                }
            }
        }
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("可释放 " + Format.bytes(totalChecked), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall)
            Button(onClick = { vm.clean() }) { Text("清理选中") }
        }
    }
}

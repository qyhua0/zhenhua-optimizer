package top.modelx.droidcare.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.modelx.droidcare.app.AppItem
import top.modelx.droidcare.app.AppRepo
import top.modelx.droidcare.core.Format
import top.modelx.droidcare.core.MemInfo
import top.modelx.droidcare.core.Perm
import top.modelx.droidcare.process.ProcRepo
import top.modelx.droidcare.process.RunningProc
import top.modelx.droidcare.process.StopGuide
import top.modelx.droidcare.process.UsageRepo
import top.modelx.droidcare.shizuku.ShizukuRepo

class RunViewModel(app: Application) : AndroidViewModel(app) {
    var mem by mutableStateOf<MemInfo.Snapshot?>(null)
    var load by mutableStateOf<Triple<Double, Double, Double>?>(null)
    var usage by mutableStateOf<List<top.modelx.droidcare.process.UsageItem>>(emptyList())
    var procs by mutableStateOf<List<RunningProc>>(emptyList())
    var disabledApps by mutableStateOf<List<AppItem>>(emptyList())
    var geek by mutableStateOf(false)
    var shizGranted by mutableStateOf(false)
    var hasUsage by mutableStateOf(Perm.hasUsageAccess(app))
    private val labels = HashMap<String, String>()
    private val procRepo = ProcRepo(app)

    init {
        refresh()
        loadDisabled()
        viewModelScope.launch {
            while (isActive) {
                delay(3000)
                refresh()
            }
        }
    }

    fun label(pkg: String): String {
        labels[pkg]?.let { return it }
        val l = try {
            val pm = getApplication<Application>().packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
        labels[pkg] = l
        return l
    }

    fun loadDisabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = try { AppRepo(getApplication()).load(withStats = false) } catch (e: Exception) { emptyList() }
            disabledApps = all.filter { !it.enabled }
        }
    }

    /** 真正停止(极客模式); 返回 false 表示不可用, 由 UI 引导系统页面 */
    fun stopApp(pkg: String): Boolean {
        val ok = ShizukuRepo.ready && ShizukuRepo.forceStop(pkg)
        if (ok) refreshNow()
        return ok
    }

    /** 返回 false 表示无直接权限, 需引导系统页面 */
    fun setEnabled(pkg: String, enabled: Boolean): Boolean {
        val ok = (ShizukuRepo.ready && ShizukuRepo.setEnabled(pkg, enabled))
            || procRepo.trySetEnabled(pkg, enabled)
        if (ok) { loadDisabled(); refreshNow() }
        return ok
    }

    private fun impMap(): Map<Int, Int> = try {
        val am = getApplication<Application>()
            .getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        am.runningAppProcesses?.associate { it.pid to it.importance } ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /** 页面进入/从系统页返回时调用, 立即拉最新状态 */
    fun refreshNow() {
        refresh()
        loadDisabled()
    }

    private fun refresh() {
        mem = MemInfo.read()
        load = MemInfo.loadAvg()
        shizGranted = ShizukuRepo.permissionGranted
        if (!hasUsage) hasUsage = Perm.hasUsageAccess(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            geek = ShizukuRepo.ready
            procs = if (ShizukuRepo.ready) ShizukuRepo.procs(impMap())
                    else procRepo.runningProcs()
        }
        if (hasUsage && usage.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                usage = UsageRepo(getApplication()).recentForeground(3)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(vm: RunViewModel = viewModel()) {
    val ctx = LocalContext.current
    var stopTarget by remember { mutableStateOf<String?>(null) }
    var toggleTarget by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // pkg, wantEnable
    // 从系统页面(强行停止/停用/启用)返回后立即刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshNow()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 从其它标签页切回时主动刷新
    LaunchedEffect(Unit) { vm.refreshNow() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("运行状态", "内存/CPU 实况 + 运行中的服务")
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("内存", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge("系统引导模式", MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(8.dp))
            val m = vm.mem
            if (m != null) {
                val used = m.totalKb - m.availKb
                LinearProgressIndicator(
                    progress = { Format.percent(used, m.totalKb) / 100f },
                    modifier = Modifier.fillMaxWidth().height(10.dp))
                Spacer(Modifier.height(6.dp))
                Text(String.format("已用 %s / 共 %s(可用 %s)",
                    Format.bytes(used * 1024), Format.bytes(m.totalKb * 1024),
                    Format.bytes(m.availKb * 1024)),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("读取 /proc/meminfo 失败", style = MaterialTheme.typography.bodySmall)
            }
            vm.load?.let { l ->
                Spacer(Modifier.height(4.dp))
                Text(String.format("CPU 负载(1/5/15min): %.2f / %.2f / %.2f",
                    l.first, l.second, l.third),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- 当前运行的服务 ----
        val procs = vm.procs
        val memVisible = procs.any { it.rssBytes > 0 && it.pkg != Perm.currentPkg }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前运行的服务", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                if (vm.geek) Badge("极客模式", MaterialTheme.colorScheme.primary)
                TextButton(onClick = {
                    try {
                        ctx.startActivity(Perm.devSettingsIntent()
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { /* ignore */ }
                }) { Text("系统面板") }
            }
            Text(when {
                vm.geek -> "极客模式: 全部进程+真实内存(按内存排序), 「停止/停用」立即生效。"
                else -> "按内存占用排序。点右侧按钮可停用(引导)。标准 Android 8+ 限制第三方读取其它应用进程/内存; 安装并启动 Shizuku 可开启极客模式。"
            },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!vm.geek && ShizukuRepo.binderAlive) {
                Spacer(Modifier.height(6.dp))
                if (vm.shizGranted) {
                    Text("已授权, 正在绑定 Shizuku 服务…若长时间未就绪请点重新绑定",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { ShizukuRepo.requestPermission() }) {
                        Text("重新绑定")
                    }
                } else {
                    OutlinedButton(onClick = { ShizukuRepo.requestPermission() }) {
                        Text("开启极客模式(Shizuku 授权)")
                    }
                }
            }
        }
        if (procs.isNotEmpty()) {
            LazyColumn(Modifier.weight(1.4f)) {
                items(procs, key = { "${it.pkg}:${it.pid}" }) { p ->
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.label(p.pkg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                Badge(p.importanceLabel, MaterialTheme.colorScheme.outline)
                            }
                            Text(p.procName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (p.rssBytes > 0) Format.bytes(p.rssBytes) else "-",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        if (p.pkg == Perm.currentPkg) {
                            Text("本应用", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            if (vm.geek) {
                                TextButton(onClick = {
                                    if (ShizukuRepo.forceStop(p.pkg)) {
                                        Toast.makeText(ctx, "已停止 " + vm.label(p.pkg),
                                            Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("停止") }
                            }
                            TextButton(onClick = { toggleTarget = p.pkg to false }) { Text("停用") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        // ---- 已停用的应用 ----
        val dis = vm.disabledApps
        if (dis.isNotEmpty()) {
            SectionCard {
                Text("已停用的应用 (${dis.size})", style = MaterialTheme.typography.titleSmall)
                dis.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.label, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { toggleTarget = a.pkg to true }) { Text("启用") }
                    }
                }
            }
        }

        // ---- 近3小时前台占用 ----
        SectionCard {
            Text("近3小时前台占用", style = MaterialTheme.typography.titleSmall)
            if (!vm.hasUsage) {
                Spacer(Modifier.height(4.dp))
                Text("需要「使用情况访问」授权", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    ctx.startActivity(Perm.usageAccessIntent()
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("去授权") }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("点「停止」跳转系统页面执行强行停止; 点「停用」在系统页面禁用应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (vm.hasUsage) {
            val list = vm.usage
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.pkg }) { u ->
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(vm.label(u.pkg), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(u.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(Format.minutes(u.foregroundMs / 60000),
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(4.dp))
                            if (u.pkg != Perm.currentPkg) {
                                TextButton(onClick = {
                                    if (!vm.stopApp(u.pkg)) stopTarget = u.pkg
                                }) { Text("停止") }
                                TextButton(onClick = { toggleTarget = u.pkg to false }) { Text("停用") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }

    stopTarget?.let { pkg ->
        AlertDialog(
            onDismissRequest = { stopTarget = null },
            title = { Text("通过系统设置停止应用") },
            text = {
                Column {
                    Text(vm.label(pkg), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(StopGuide.vendorHint(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(StopGuide.WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100))
                }
            },
            confirmButton = {
                Button(onClick = {
                    stopTarget = null
                    try {
                        ctx.startActivity(Perm.appDetailsIntent(pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { /* ignore */ }
                }) { Text("前往系统页面") }
            },
            dismissButton = { TextButton(onClick = { stopTarget = null }) { Text("取消") } },
        )
    }

    toggleTarget?.let { (pkg, wantEnable) ->
        AlertDialog(
            onDismissRequest = { toggleTarget = null },
            title = { Text(if (wantEnable) "启用应用" else "停用应用") },
            text = {
                Column {
                    Text(vm.label(pkg), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(if (wantEnable)
                        "将尝试直接启用; 若无权限则跳转到系统应用信息页, 请在页面菜单中选择「启用」。"
                    else
                        "将尝试直接停用; 若无权限则跳转到系统应用信息页, 请点击右上角菜单(⋮)选择「停用」。停用后应用不会自启和后台运行, 可随时在此处启用。",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(StopGuide.WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100))
                }
            },
            confirmButton = {
                Button(onClick = {
                    toggleTarget = null
                    if (!vm.setEnabled(pkg, wantEnable)) {
                        try {
                            ctx.startActivity(Perm.appDetailsIntent(pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { /* ignore */ }
                    } else {
                        Toast.makeText(ctx,
                            (if (wantEnable) "已启用 " else "已停用 ") + vm.label(pkg),
                            Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { toggleTarget = null }) { Text("取消") } },
        )
    }
}

package top.modelx.droidcare.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import top.modelx.droidcare.process.TaskInfo
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
    var tasks by mutableStateOf<List<TaskInfo>>(emptyList())
    private val labels = HashMap<String, String>()
    private val procRepo = ProcRepo(app)
    private var lastTicks: Map<Int, Long>? = null
    private var lastTs = 0L
    private var netCache: Map<String, LongArray> = emptyMap()
    private var netTs = 0L
    private data class Meta(val systemApp: Boolean, val internet: Boolean, val suspended: Boolean, val suspicious: Boolean)
    private val metaCache = HashMap<String, Meta>()

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
            metaCache.clear()
        }
    }

    /** 包元信息: 系统应用/有网络权限/已停用/疑似广告组件 */
    private fun metaOf(pkg: String): Meta = metaCache.getOrPut(pkg) {
        var sys = false
        var susp = false
        try {
            val pm = getApplication<Application>().packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            sys = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            susp = !ai.enabled
        } catch (e: Exception) { /* ignore */ }
        val internet = try {
            getApplication<Application>().packageManager
                .checkPermission(android.Manifest.permission.INTERNET, pkg) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
        val sus = sys && internet && TaskInfo.suspect(pkg, label(pkg))
        Meta(sys, internet, susp, sus)
    }

    /** 结束单个进程(shell kill -9) */
    fun killPid(pid: Int): Boolean {
        val ok = ShizukuRepo.ready && ShizukuRepo.killPid(pid)
        if (ok) refreshNow()
        return ok
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
        if (ok && !enabled && ShizukuRepo.ready) ShizukuRepo.forceStop(pkg)
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
            if (ShizukuRepo.ready) {
                val now = System.currentTimeMillis()
                if (now - netTs > 20000 || netCache.isEmpty()) {
                    netCache = ShizukuRepo.netByPkg()
                    netTs = now
                }
                val rows = ShizukuRepo.procRows().filter { !it.third.contains('/') }
                val rss = ShizukuRepo.rssMap()
                val ticks = ShizukuRepo.ticksMap()
                val imp = impMap()
                val prev = lastTicks
                val dt = now - lastTs
                tasks = rows.map { (pid, user, name) ->
                    val pkg = name.substringBefore(':')
                    val meta = metaOf(pkg)
                    val cpu = if (prev != null && ticks != null && dt in 500..15000) {
                        val d = (ticks[pid] ?: 0L) - (prev[pid] ?: 0L)
                        if (d < 0) 0.0 else d * 1000.0 / dt
                    } else -1.0
                    val net = netCache[pkg]
                    TaskInfo(pid, pkg, name, user, rss[pid] ?: 0L, cpu,
                        net?.get(1) ?: -1L, net?.get(0) ?: -1L,
                        imp[pid] ?: 300, meta.systemApp, meta.internet,
                        meta.suspicious, meta.suspended)
                }
                if (ticks != null) { lastTicks = ticks; lastTs = now }
                procs = ShizukuRepo.procs(imp)
            } else {
                val ps = procRepo.runningProcs()
                procs = ps
                tasks = ps.map {
                    val meta = metaOf(it.pkg)
                    TaskInfo(it.pid, it.pkg, it.procName, "", it.rssBytes, -1.0, -1L, -1L,
                        it.importance, meta.systemApp, meta.internet, meta.suspicious, meta.suspended)
                }
            }
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
    var detailPid by remember { mutableStateOf<Int?>(null) }
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
        ScreenHeader("任务管理", "进程资源占用 · 结束/停用应用与服务")
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

        // ---- 任务列表 ----
        var taskSort by remember { mutableStateOf("mem") }
        var taskFilter by remember { mutableStateOf("all") }
        val susCount = vm.tasks.count { it.suspicious }
        val shown = remember(vm.tasks, taskSort, taskFilter) {
            val f = when (taskFilter) {
                "third" -> vm.tasks.filter { !it.systemApp }
                "system" -> vm.tasks.filter { it.systemApp }
                "sus" -> vm.tasks.filter { it.suspicious }
                else -> vm.tasks
            }
            when (taskSort) {
                "cpu" -> f.sortedByDescending { it.cpuPct }
                "net" -> f.sortedByDescending { it.txBytes }
                else -> f.sortedByDescending { it.rssBytes }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("运行中的任务 (${vm.tasks.size})", style = MaterialTheme.typography.titleSmall,
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
                vm.geek -> "CPU/内存/流量每 3 秒刷新。点任务行可结束进程/停止/停用。「疑似」= 预装+有网络权限+广告/推送类包名或名称, 仅供参考。"
                else -> "标准 Android 8+ 限制第三方读取其它应用进程; 安装并启动 Shizuku 可查看全部进程与真实占用, 并直接结束/停用。"
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
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(taskFilter == "all", { taskFilter = "all" }, { Text("全部") })
                FilterChip(taskFilter == "third", { taskFilter = "third" }, { Text("第三方") })
                FilterChip(taskFilter == "system", { taskFilter = "system" }, { Text("系统") })
                FilterChip(taskFilter == "sus", { taskFilter = "sus" },
                    { Text(if (susCount > 0) "疑似 $susCount" else "疑似") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(taskSort == "mem", { taskSort = "mem" }, { Text("按内存") })
                FilterChip(taskSort == "cpu", { taskSort = "cpu" }, { Text("按 CPU") })
                FilterChip(taskSort == "net", { taskSort = "net" }, { Text("按上行") })
            }
        }
        LazyColumn(Modifier.weight(1.4f)) {
            items(shown, key = { "${it.pkg}:${it.pid}" }) { t ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (t.pkg != Perm.currentPkg) detailPid = t.pid }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.label(t.pkg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Badge(t.importanceLabel, MaterialTheme.colorScheme.outline)
                            if (t.suspicious) {
                                Spacer(Modifier.width(4.dp))
                                Badge("疑似", Color(0xFFC62828))
                            }
                            if (t.suspended) {
                                Spacer(Modifier.width(4.dp))
                                Badge("已停用", MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        Text(t.procName + " · " + t.user,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(String.format("%s%s", if (t.cpuPct >= 0) String.format("CPU %.1f%%  ", t.cpuPct) else "",
                            if (t.rssBytes > 0) Format.bytes(t.rssBytes) else "-"),
                            style = MaterialTheme.typography.bodySmall)
                        if (t.txBytes >= 0) {
                            Text(String.format("↑%s ↓%s", Format.bytes(t.txBytes), Format.bytes(t.rxBytes)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
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
                Text("上表「运行中的任务」可直接结束/停用(需极客模式); 此处的停止在未授权时会引导系统页面。",
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

    // ---- 任务详情弹层 ----
    detailPid?.let { pid ->
        val t = vm.tasks.firstOrNull { it.pid == pid }
        ModalBottomSheet(onDismissRequest = { detailPid = null }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (t == null) {
                    Text("该进程已退出", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.label(t.pkg), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.systemApp) Badge("系统应用", MaterialTheme.colorScheme.tertiary)
                    if (t.suspicious) {
                        Spacer(Modifier.width(4.dp))
                        Badge("疑似广告/推送", Color(0xFFC62828))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(t.procName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(String.format("PID %d · %s · %s", t.pid, t.user, t.importanceLabel),
                    style = MaterialTheme.typography.bodyMedium)
                Text(String.format("内存 %s · CPU %s",
                    if (t.rssBytes > 0) Format.bytes(t.rssBytes) else "-",
                    if (t.cpuPct >= 0) String.format("%.1f%%", t.cpuPct) else "-"),
                    style = MaterialTheme.typography.bodyMedium)
                if (t.txBytes >= 0) {
                    Text(String.format("开机以来网络: 上行 %s / 下行 %s%s", Format.bytes(t.txBytes),
                        Format.bytes(t.rxBytes), if (t.hasInternet) "" else " (无网络权限)"),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (t.suspicious) {
                    Spacer(Modifier.height(8.dp))
                    Text("预装应用且带网络权限, 包名/名称命中广告、推送、遥测类特征。停用前建议先查一下该包用途; 停用后如需恢复可在此启用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100))
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (ShizukuRepo.forceStop(t.pkg)) {
                            Toast.makeText(ctx, "已停止 " + vm.label(t.pkg), Toast.LENGTH_SHORT).show()
                            detailPid = null
                        } else {
                            Toast.makeText(ctx, "停止失败(可能已退出或需重新授权)", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("停止应用") }
                    OutlinedButton(onClick = {
                        if (vm.killPid(pid)) {
                            Toast.makeText(ctx, "已结束进程 $pid", Toast.LENGTH_SHORT).show()
                            detailPid = null
                        } else {
                            Toast.makeText(ctx, "结束失败(需极客模式)", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("结束进程") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        detailPid = null
                        toggleTarget = t.pkg to t.suspended
                    }) { Text(if (t.suspended) "启用应用" else "停用应用") }
                    OutlinedButton(onClick = {
                        try {
                            ctx.startActivity(Perm.appDetailsIntent(t.pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { /* ignore */ }
                    }) { Text("应用信息") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
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

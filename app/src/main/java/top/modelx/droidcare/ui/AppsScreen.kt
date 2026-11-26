package top.modelx.droidcare.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.modelx.droidcare.app.AppItem
import top.modelx.droidcare.app.AppRepo
import top.modelx.droidcare.core.Format
import top.modelx.droidcare.core.Perm
import java.text.SimpleDateFormat
import java.util.Locale

enum class AppSort { SIZE, INSTALL, NAME }
enum class AppFilter { ALL, USER, SYSTEM }

private val DANGEROUS_PERMS = setOf(
    "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG",
    "READ_PHONE_STATE", "CALL_PHONE", "SEND_SMS", "RECEIVE_SMS", "READ_SMS",
    "READ_CALENDAR", "WRITE_CALENDAR",
    "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
    "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
    "POST_NOTIFICATIONS", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES",
)

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    var items by mutableStateOf<List<AppItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
    var sort by mutableStateOf(AppSort.SIZE)
    var filter by mutableStateOf(AppFilter.ALL)
    var detail by mutableStateOf<AppItem?>(null)

    private val repo = AppRepo(app)

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            loading = true
            val list = kotlinx.coroutines.withContext(Dispatchers.IO) {
                repo.load(withStats = true)
            }
            items = list
            loading = false
        }
    }

    fun filtered(): List<AppItem> {
        var l = items
        l = when (filter) {
            AppFilter.ALL -> l
            AppFilter.USER -> l.filter { !it.isSystem }
            AppFilter.SYSTEM -> l.filter { it.isSystem }
        }
        l = when (sort) {
            AppSort.SIZE -> l.sortedByDescending { it.totalBytes }
            AppSort.INSTALL -> l.sortedByDescending { it.installedAt }
            AppSort.NAME -> l.sortedBy { it.label }
        }
        return l
    }

    fun dangerousPerms(pkg: String): List<String> {
        val ctx = getApplication<Application>()
        return try {
            val pi = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            (pi.requestedPermissions?.toList() ?: emptyList())
                .map { it.substringAfterLast('.') }
                .filter { it in DANGEROUS_PERMS }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: AppsViewModel = viewModel()) {
    val ctx = LocalContext()
    // 从系统设置授权返回后自动刷新
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 从其它标签页切回时 ViewModel 保留, 主动重拉避免显示旧状态
    LaunchedEffect(Unit) { vm.reload() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("应用管理",
            if (Perm.hasUsageAccess(ctx)) null else "提示: 开启「使用情况访问」可显示应用大小")
        if (!vm.loading) {
            val userCount = vm.items.count { !it.isSystem }
            if (userCount <= 1) {
                SectionCard {
                    Text("看不到应用列表？", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("系统限制了「读取已安装应用」权限, 目前只能看到本应用。请到系统设置中允许 DroidCare 读取应用列表(小米/华为/OPPO/vivo 一般在: 应用设置 → 应用管理 → DroidCare → 权限 → 获取已安装应用列表), 然后返回本页自动刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            try {
                                ctx.startActivity(Perm.appDetailsIntent(Perm.currentPkg)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) { /* ignore */ }
                        }) { Text("去系统授权") }
                        OutlinedButton(onClick = { vm.reload() }) { Text("手动刷新") }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppFilter.entries.forEach { f ->
                FilterChip(
                    selected = vm.filter == f,
                    onClick = { vm.filter = f },
                    label = { Text(f.label()) },
                )
            }
            Spacer(Modifier.weight(1f))
            SortMenu(vm)
        }
        Spacer(Modifier.height(8.dp))
        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val list = vm.filtered()
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.pkg }) { item ->
                    AppRow(item) { vm.detail = item }
                    HorizontalDivider()
                }
            }
        }
    }
    vm.detail?.let { item ->
        AppDetailDialog(vm, item, onDismiss = { vm.detail = null })
    }
}

private fun AppFilter.label(): String = when (this) {
    AppFilter.ALL -> "全部"; AppFilter.USER -> "已安装"; AppFilter.SYSTEM -> "系统"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortMenu(vm: AppsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("排序: " + when (vm.sort) {
                AppSort.SIZE -> "大小"; AppSort.INSTALL -> "安装时间"; AppSort.NAME -> "名称"
            })
        }
        DropdownMenu(expanded, { expanded = false }) {
            AppSort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(when (s) {
                    AppSort.SIZE -> "按大小"
                    AppSort.INSTALL -> "按安装时间"
                    AppSort.NAME -> "按名称"
                }) }, onClick = { vm.sort = s; expanded = false })
            }
        }
    }
}

@Composable
private fun LocalContext(): android.content.Context =
    androidx.compose.ui.platform.LocalContext.current

@Composable
private fun AppRow(item: AppItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge)
                if (item.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    Badge("系统", MaterialTheme.colorScheme.outline)
                }
                if (!item.enabled) {
                    Spacer(Modifier.width(6.dp))
                    Badge("已停用", MaterialTheme.colorScheme.error)
                }
            }
            Text(item.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (item.totalBytes > 0) Format.bytes(item.totalBytes) else "-",
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AppDetailDialog(vm: AppsViewModel, item: AppItem, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val perms = remember(item.pkg) { vm.dangerousPerms(item.pkg) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.label) },
        text = {
            Column {
                val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                DetailLine("包名", item.pkg)
                DetailLine("版本", item.versionName)
                DetailLine("targetSdk", String.format(Locale.US, "%d", item.targetSdk))
                DetailLine("安装于", df.format(java.util.Date(item.installedAt)))
                if (item.totalBytes > 0) {
                    DetailLine("APK", Format.bytes(item.installBytes))
                    DetailLine("数据", Format.bytes(item.dataBytes))
                    DetailLine("缓存", Format.bytes(item.cacheBytes))
                }
                if (perms.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("敏感权限: " + perms.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!item.isSystem) {
                        Button(onClick = {
                            try {
                                ctx.startActivity(Perm.uninstallIntent(item.pkg)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) { /* ignore */ }
                        }) { Text("卸载") }
                    }
                    OutlinedButton(onClick = {
                        try {
                            ctx.startActivity(Perm.appDetailsIntent(item.pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { /* ignore */ }
                    }) { Text("应用信息") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DetailLine(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}

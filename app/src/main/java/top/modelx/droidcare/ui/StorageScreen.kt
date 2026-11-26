package top.modelx.droidcare.ui

import android.app.Application
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import top.modelx.droidcare.core.Format
import top.modelx.droidcare.storage.DirEntry
import top.modelx.droidcare.storage.StorageRepo
import top.modelx.droidcare.storage.VolumeInfo

class StorageViewModel(app: Application) : AndroidViewModel(app) {
    val repo = StorageRepo(app)
    var volumes by mutableStateOf<List<VolumeInfo>>(emptyList())
    var categories by mutableStateOf<List<StorageRepo.Category>>(emptyList())
    var pathStack by mutableStateOf(listOf(Environment.getExternalStorageDirectory().absolutePath))
    var entries by mutableStateOf<List<DirEntry>>(emptyList())
    var loadingDir by mutableStateOf(false)
    var largeFiles by mutableStateOf<List<DirEntry>?>(null)
    var scanningLarge by mutableStateOf(false)
    var orphans by mutableStateOf<List<Pair<String, Long>>?>(null)
    var scanningOrphan by mutableStateOf(false)

    fun scanOrphans() {
        if (scanningOrphan) return
        scanningOrphan = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    top.modelx.droidcare.app.AppRepo(getApplication()).orphanDirs()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            orphans = list
            scanningOrphan = false
        }
    }

    /** 返回 true=成功移入回收站 */
    fun deleteOrphan(path: String): Boolean {
        val trash = top.modelx.droidcare.data.TrashManager(getApplication())
        val ok = trash.moveToTrash(java.io.File(path))
        if (ok) orphans = orphans?.filter { it.first != path }
        return ok
    }

    init {
        viewModelScope.launch {
            val r = repo
            volumes = withContext(Dispatchers.IO) { r.volumes() }
            categories = withContext(Dispatchers.IO) { r.volumeCategories() }
            loadDir(pathStack.last())
        }
    }

    fun loadDir(path: String) {
        viewModelScope.launch {
            loadingDir = true
            entries = withContext(Dispatchers.IO) { repo.loadDir(path) }
            loadingDir = false
        }
    }

    fun enter(e: DirEntry) {
        if (!e.isDir) return
        pathStack = pathStack + e.path
        loadDir(e.path)
    }

    fun up() {
        if (pathStack.size > 1) {
            pathStack = pathStack.dropLast(1)
            loadDir(pathStack.last())
        }
    }

    fun scanLarge() {
        if (scanningLarge) return
        scanningLarge = true
        viewModelScope.launch {
            val root = Environment.getExternalStorageDirectory()
            val res = withContext(Dispatchers.IO) {
                repo.largeFiles(root, 50L * 1024 * 1024)
            }
            largeFiles = res
            scanningLarge = false
        }
    }
}

@Composable
fun StorageScreen(vm: StorageViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("存储分析", "分类占比 + 目录钻取 + 大文件")
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("总览") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("目录") })
            Tab(selected = tab == 2, onClick = { tab = 2; vm.scanLarge() },
                text = { Text("大文件") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("残留") })
        }
        when (tab) {
            0 -> OverviewTab(vm)
            1 -> DirTab(vm)
            2 -> LargeTab(vm)
            3 -> OrphanTab(vm)
        }
    }
}

@Composable
private fun OverviewTab(vm: StorageViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)) {
        val v = vm.volumes.firstOrNull()
        if (v != null) {
            val used = v.total - v.free
            SectionCard {
                StorageRing(Format.percent(used, v.total), v.label)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("已用", Format.bytes(used), Modifier.weight(1f))
                    Stat("可用", Format.bytes(v.free), Modifier.weight(1f))
                    Stat("总量", Format.bytes(v.total), Modifier.weight(1f))
                }
            }
        }
        if (vm.categories.isNotEmpty()) {
            val total = vm.categories.sumOf { it.bytes }
            SectionCard {
                Text("系统分类统计", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                CategoryBar(vm.categories, total)
                Spacer(Modifier.height(8.dp))
                vm.categories.forEach { c ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(c.name, Modifier.width(60.dp),
                            style = MaterialTheme.typography.bodySmall)
                        Text(Format.bytes(c.bytes),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            SectionCard {
                Text("此 ROM 不提供分类统计接口, 可切到「目录」页签逐层查看。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Stat(k: String, v: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(v, style = MaterialTheme.typography.titleSmall)
        Text(k, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryBar(categories: List<StorageRepo.Category>, total: Long) {
    val palette = listOf(
        Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFF57C00), Color(0xFF7B1FA2),
        Color(0xFF00796B), Color(0xFFC2185B), Color(0xFF5D4037), Color(0xFF607D8B))
    Row(Modifier.fillMaxWidth().height(14.dp).clipToRoundRect()) {
        categories.forEachIndexed { i, c ->
            val w = if (total > 0) c.bytes.toFloat() / total.toFloat() else 0f
            if (w > 0.001f) {
                Spacer(Modifier.weight(w).fillMaxHeight()
                    .background(palette[i % palette.size]))
            }
        }
    }
}

private fun Modifier.clipToRoundRect(): Modifier =
    this.then(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)))

@Composable
private fun DirTab(vm: StorageViewModel) {
    Column(Modifier.fillMaxSize()) {
        // 面包屑
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.pathStack.size > 1) {
                TextButton(onClick = { vm.up() }) { Text("↑ 上级") }
            }
            val cur = vm.pathStack.last()
            Text(cur, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
        if (vm.loadingDir) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.entries, key = { it.path }) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = e.isDir) { vm.enter(e) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (e.isDir) "📁 " else "📄 ", style = MaterialTheme.typography.bodyMedium)
                        Text(e.name, Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(Format.bytes(e.size), style = MaterialTheme.typography.bodySmall,
                            color = if (e.size >= 100L * 1024 * 1024)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrphanTab(vm: StorageViewModel) {
    if (android.os.Build.VERSION.SDK_INT >= 30 &&
        !top.modelx.droidcare.core.Perm.hasAllFileAccess()) {
        SectionCard {
            Text("需要「所有文件访问」权限才能扫描 Android/ 目录残留",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            val ctx = LocalContext.current
            Button(onClick = {
                ctx.startActivity(
                    top.modelx.droidcare.core.Perm.manageStorageIntent()
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("去授权") }
        }
        return
    }
    if (vm.scanningOrphan) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在扫描卸载残留…")
            }
        }
    } else {
        val list = vm.orphans
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { vm.scanOrphans() }) { Text("扫描卸载残留") }
                    Spacer(Modifier.height(8.dp))
                    Text("检查 Android/data|media|obb 中已卸载应用留下的目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未发现残留目录 🎉")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Text(String.format("发现 %d 个残留目录, 共 %s · 删除=移入回收站(7天可还原)",
                        list.size, Format.bytes(list.sumOf { it.second })),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp))
                }
                items(list, key = { it.first }) { (path, size) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(path.substringAfterLast('/'), maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(Format.bytes(size), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { vm.deleteOrphan(path) }) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LargeTab(vm: StorageViewModel) {
    if (vm.scanningLarge) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在扫描 ≥50MB 文件…")
            }
        }
    } else {
        val list = vm.largeFiles
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { vm.scanLarge() }) { Text("扫描大文件") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                item {
                    Text(String.format("共 %d 个 ≥50MB 文件(前100)", list.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp))
                }
                items(list, key = { it.path }) { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.path, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(Format.bytes(e.size), color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

package top.modelx.droidcare.ui

import android.app.Application
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
import androidx.lifecycle.viewmodel.compose.viewModel
import top.modelx.droidcare.data.Prefs
import top.modelx.droidcare.shizuku.ShizukuRepo

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    var shizTick by mutableStateOf(0)

    /** Shizuku 状态文本(读 shizTick 触发重组) */
    fun shizStatus(): String {
        shizTick
        return ShizukuRepo.statusText()
    }

    fun shizRefresh() { shizTick++ }

    fun shizRequest() { ShizukuRepo.requestPermission(); shizTick++ }
    var whitelist by mutableStateOf(prefs.userWhitelist().toList())
    var input by mutableStateOf("")

    fun add() {
        val v = input.trim()
        if (v.startsWith("/")) {
            prefs.addUserWhitelist(v)
            whitelist = prefs.userWhitelist().toList()
            input = ""
        }
    }

    fun remove(v: String) {
        prefs.removeUserWhitelist(v)
        whitelist = prefs.userWhitelist().toList()
    }

    fun defaults(ownPkg: String): List<String> {
        val user = prefs.userWhitelist()
        return prefs.whitelist(ownPkg).filter { it !in user }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("设置", "白名单内的路径及其子目录不参与扫描清理")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("极客模式 (Shizuku)", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        Badge(vm.shizStatus().substringBefore("("),
                            if (ShizukuRepo.ready) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("状态: " + vm.shizStatus(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("开启后可: 查看全部进程真实内存、一键强制停止、直接停用/启用任意应用(无需跳系统页面)。需先安装并启动 Shizuku(ADB/无线调试激活)。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.shizRequest() }) { Text("请求授权") }
                        OutlinedButton(onClick = { vm.shizRefresh() }) { Text("刷新状态") }
                    }
                }
            }
            item {
                SectionCard {
                    Text("添加白名单(绝对路径前缀)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.input,
                            onValueChange = { vm.input = it },
                            placeholder = { Text("/storage/emulated/0/MyPhotos") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.add() }) { Text("添加") }
                    }
                }
            }
            if (vm.whitelist.isNotEmpty()) {
                item {
                    Text("我的白名单", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                items(vm.whitelist) { w ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w, Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.remove(w) }) { Text("移除") }
                    }
                }
            }
            item {
                Text("默认保护(不可移除)", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(vm.defaults(ownPkg = "top.modelx.droidcare")) { d ->
                Text(d,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            item {
                SectionCard {
                    Text("关于与承诺", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("· 文件内容绝不上传, 全部本地处理\n" +
                        "· 不做「一键加速杀后台」; 结束进程通过系统页面完成\n" +
                        "· 清理=软删除进回收站, 7 天内可还原\n" + "· v1.0.0 开源版",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

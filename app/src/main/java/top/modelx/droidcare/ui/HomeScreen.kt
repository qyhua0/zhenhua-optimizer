package top.modelx.droidcare.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import top.modelx.droidcare.core.Format
import top.modelx.droidcare.core.MemInfo
import top.modelx.droidcare.data.Prefs
import top.modelx.droidcare.data.TrashManager
import top.modelx.droidcare.storage.StorageRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StorageRepo(app)
    val volumes = repo.volumes()
    val mem = MemInfo.read()
    val trashCount = TrashManager(app).list().size
    val lastFreed = Prefs(app).lastCleanFreed
    val lastAt = Prefs(app).lastCleanAt
}

@Composable
fun HomeScreen(open: (String) -> Unit, vm: HomeViewModel = viewModel()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)) {
        ScreenHeader("DroidCare", "透明 · 不打扰 · 不自动杀后台")
        val v = vm.volumes.firstOrNull()
        if (v != null) {
            val used = v.total - v.free
            SectionCard {
                StorageRing(Format.percent(used, v.total), v.label, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat2("已用", Format.bytes(used), Modifier.weight(1f))
                    Stat2("可用", Format.bytes(v.free), Modifier.weight(1f))
                    Stat2("总量", Format.bytes(v.total), Modifier.weight(1f))
                }
            }
        }
        SectionCard {
            Text("运行内存", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val m = vm.mem
            if (m != null) {
                val used = m.totalKb - m.availKb
                LinearProgressIndicator(
                    progress = { Format.percent(used, m.totalKb) / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp))
                Spacer(Modifier.height(4.dp))
                Text(String.format("可用 %s / 共 %s",
                    Format.bytes(m.availKb * 1024), Format.bytes(m.totalKb * 1024)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (vm.lastAt > 0) {
            val df = SimpleDateFormat("MM-dd HH:mm", Locale.US)
            SectionCard {
                Text("上次清理: " + df.format(Date(vm.lastAt)) +
                    " · 释放 " + Format.bytes(vm.lastFreed),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("回收站 " + vm.trashCount + " 项 · 7天后自动清除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            NavCard("垃圾清理", "缓存/临时/广告文件", Modifier.weight(1f)) { open("clean") }
            Spacer(Modifier.width(12.dp))
            NavCard("应用管理", "列表/大小/卸载", Modifier.weight(1f)) { open("apps") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            NavCard("存储分析", "分类/目录/大文件", Modifier.weight(1f)) { open("storage") }
            Spacer(Modifier.width(12.dp))
            NavCard("运行状态", "内存/进程引导", Modifier.weight(1f)) { open("run") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            NavCard("回收站", vm.trashCount.toString() + " 项可还原", Modifier.weight(1f)) { open("trash") }
            Spacer(Modifier.width(12.dp))
            NavCard("设置", "白名单/关于", Modifier.weight(1f)) { open("settings") }
        }
    }
}

@Composable
fun StorageRing(percent: Int, label: String, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val d = size.minDimension - stroke
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - d) / 2, (size.height - d) / 2)
            drawArc(
                color = trackColor,
                0f, 360f, useCenter = false,
                topLeft = topLeft, size = Size(d, d),
                style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                color = if (percent > 85) Color(0xFFC62828) else Color(0xFF1976D2),
                -90f, 360f * percent / 100f, useCenter = false,
                topLeft = topLeft, size = Size(d, d),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(String.format(Locale.US, "%d%%", percent),
                style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat2(k: String, v: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(v, style = MaterialTheme.typography.titleSmall)
        Text(k, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavCard(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

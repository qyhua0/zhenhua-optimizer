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
import top.modelx.droidcare.core.Format
import top.modelx.droidcare.data.TrashManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrashViewModel(app: Application) : AndroidViewModel(app) {
    private val trash = TrashManager(app)
    var entries by mutableStateOf(trash.list())

    fun refresh() { entries = trash.list() }

    fun restore(e: TrashManager.Entry): Boolean {
        val ok = trash.restore(e)
        refresh()
        return ok
    }

    fun delete(e: TrashManager.Entry): Boolean {
        val ok = trash.deleteForever(e)
        refresh()
        return ok
    }
}

@Composable
fun TrashScreen(vm: TrashViewModel = viewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("回收站", "清理的文件保留 7 天, 可还原到原位置")
        if (vm.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("回收站是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.entries, key = { it.id }) { e ->
                    val df = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.origPath, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(df.format(Date(e.deletedAt)) + " · " + Format.bytes(e.size),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            val ok = vm.restore(e)
                            android.widget.Toast.makeText(ctx,
                                if (ok) "已还原到原位置" else "还原失败(原路径已存在或不可写)",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("还原") }
                        TextButton(onClick = {
                            val ok = vm.delete(e)
                            android.widget.Toast.makeText(ctx,
                                if (ok) "已彻底删除" else "删除失败",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

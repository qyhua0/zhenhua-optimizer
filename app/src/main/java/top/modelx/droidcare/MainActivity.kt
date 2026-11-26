package top.modelx.droidcare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import top.modelx.droidcare.core.Perm
import top.modelx.droidcare.shizuku.ShizukuRepo
import top.modelx.droidcare.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perm.currentPkg = packageName
        ShizukuRepo.init(application)
        setContent { DroidCareTheme { DroidCareApp() } }
    }
}

@Composable
fun DroidCareTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Filled.Home),
    Tab("clean", "清理", Icons.Filled.Delete),
    Tab("apps", "应用", Icons.Filled.List),
    Tab("storage", "存储", Icons.Filled.Build),
    Tab("run", "运行", Icons.Filled.Refresh),
)

@Composable
fun DroidCareApp() {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isMainTab = tabs.any { it.route == route }

    // 切标签统一入口: 先弹回 home 再入目标, 避免同一页在栈里重复/状态串页
    val goTab: (String) -> Unit = { target ->
        if (route != target) {
            nav.navigate(target) {
                popUpTo("home") { saveState = target != "home" }
                launchSingleTop = true
                restoreState = target != "home"
            }
        }
    }
    // 卡片跳转: 目标是指页标签走 goTab; 子页(回收站/设置)直接压栈
    val openRoute: (String) -> Unit = { r ->
        if (tabs.any { it.route == r }) goTab(r)
        else nav.navigate(r) { launchSingleTop = true }
    }

    Scaffold(
        bottomBar = {
            if (isMainTab) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { goTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") { HomeScreen(open = openRoute) }
                composable("clean") { CleanScreen(open = openRoute) }
                composable("apps") { AppsScreen() }
                composable("storage") { StorageScreen() }
                composable("run") { RunScreen() }
                composable("trash") { TrashScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

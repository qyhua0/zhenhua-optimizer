# 振华优化 (Zhenhua Optimizer)

Android 清理 / 运行管理 / 应用管理工具，个人自用 + 开源。**零网络权限**，所有数据只留在你手机里。

## 安全承诺

- **零网络权限**：APK 不申请 `INTERNET` 权限，无法把任何数据发出去（可用 `aapt dump permissions` 自行验证）。
- **纯本地**：文件扫描、进程分析、存储统计全部在本机完成，不上传任何文件内容或列表。
- **软删除**：所有清理先进内置回收站，7 天内可一键还原，不做不可逆删除（回收站里的「彻底删除」除外）。
- **白名单保护**：DCIM、微信/WhatsApp 数据目录、应用私有目录等永远不会出现在清理结果里。
- **不越权**：无 root 时只使用官方 API；需要 shell 能力的功能（停止进程、免卸载停用应用）通过 [Shizuku](https://shizuku.rikka.app/) 实现，每次授权都由你在 Shizuku 里明确确认，可随时撤销。
- **不做**：静默卸载、修改系统参数、开机自启、后台常驻。

## 功能

| 模块 | 说明 |
|---|---|
| 首页 | 存储/内存概览、回收站入口、快捷导航 |
| 清理 | 缓存/缩略图/空目录/安装包/大文件/广告目录扫描，分类可折叠勾选，软删除进回收站 |
| 运行 | 进程内存排行（Shizuku 下显示全部进程 + 真实 PSS），停止/停用应用 |
| 应用 | 应用列表、占用排序、停用/启用、卸载残留目录清理 |
| 存储 | 存储卷分析、大文件、残留（卸载后遗留目录）扫描 |
| 回收站 | 7 天还原窗口，彻底删除需确认 |

### Shizuku 极客模式（可选）

安装并启动 Shizuku（ADB 或 root 方式）后，本应用可请求 shell 权限：

- `ps -A` + `/proc/*/statm`：全部进程与真实内存
- `am force-stop`：直接停止进程
- `pm disable-user` / `pm enable`：免 root 停用系统应用

不使用 Shizuku 时以上功能自动降级为引导系统页面，其余功能不受影响。

## 构建

```bash
# 环境: JDK 17, Android SDK 34
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release(需自行配置签名, 见下)
```

Release 签名：在项目根放 `signing.properties`（或修改 `app/build.gradle.kts` 中的路径），字段：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`。未配置时 release 不签名。

- minSdk 26（Android 8.0），targetSdk 34
- Kotlin 2.0 + Jetpack Compose（Material3）
- 包名 `top.modelx.droidcare`（历史原因保留，升级路径已绑定）

## 下载

- Releases 页提供已签名 release APK 与 debug APK。
- 国内加速镜像：<https://get.modelx.top/>（见 Release 说明中的镜像链接）。

## 许可

Apache License 2.0。欢迎 fork，欢迎提 issue；不提供任何"官方"背书——请始终从本仓库 Releases 或自行构建获取安装包，并核对 SHA-256。

# Mirinda

> 对话式 AI 的 Android 客户端（UI 端）。基于 Jetpack Compose 构建，支持流式对话、多模型切换、附件上传，并内置「划词查询」系统级悬浮窗。

---

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [架构说明](#架构说明)
- [后端接口约定](#后端接口约定)
- [快速开始](#快速开始)
- [所需权限](#所需权限)
- [关键配置](#关键配置)

---

## 项目简介

Mirinda 是一个对话式 AI 的 Android 前端应用，应用包名 `com.seesinlee.mirinda`。它连接后端 AI 服务，提供流畅的流式聊天体验，并通过系统文本选择工具栏（PROCESS_TEXT）实现「划词即查」——在任意 App 中选中文字即可弹出 AI 查询结果悬浮窗，不打断当前应用。

核心能力包括：

- **流式对话**：基于 SSE（Server-Sent Events），逐字渲染正文，并单独展示模型的「推理 / 思考过程」。
- **富内容消息**：一条消息由多种「内容块」组成——文字、深度思考、图片、文件（xlsx / docx / pdf 等）、工具调用提示。
- **多模型切换**：模型列表由后端 `/agent` 接口动态下发，用户可在设置中选择。
- **历史对话**：对话与消息由后端持久化，支持列表、回看与删除。
- **附件上传**：本地选择图片 / 文件后立即上传，结果以「图片气泡」或「文件卡片」展示。
- **划词查询悬浮窗**：系统级悬浮窗（前台服务 + TYPE_APPLICATION_OVERLAY），可拖动、关闭、一键「在 App 中继续」。

---

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 聊天界面 | Material 3 主题，深蓝配色；支持输入、发送、中断生成、失败重试。 |
| 流式渲染 | SSE 增量解析（reasoning / token / tool_call / file / meta 事件）。 |
| Markdown | 助手回复以 Markdown 渲染（标题、列表、代码块、加粗等）。 |
| 深度思考 | 可折叠的「推理过程」面板，与正文分离展示。 |
| 模型管理 | 启动时拉取后端模型列表，可切换当前模型并显示欢迎语。 |
| 会话管理 | 新建 / 切换 / 删除历史对话，消息按时间正序加载。 |
| 附件 | 图片缩略图预览 + 文件卡片（图标 + 文件名），支持上传与下载。 |
| 鉴权 | 基于 `x-user-token` 请求头；首次启动自动生成设备默认 token，支持设置页手动替换。 |
| 登录 / 心跳 | 启动校验登录态，定时心跳保活。 |
| 图片缓存 | Coil 自定义磁盘缓存（落 `filesDir`，256MB），App 重启仍命中，支持「清理缓存」。 |
| 划词查询 | PROCESS_TEXT 入口 → 校验悬浮窗权限 → 前台服务弹出可拖拽悬浮窗，一次性流式查询。 |

---

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **架构**：MVVM（ViewModel + 单向数据流）
- **异步 / 流式**：Kotlin Coroutines + `Flow`（冷流 SSE）
- **网络**：原生 `HttpURLConnection`（SSE 手动逐行解析，multipart 文件上传），JSON 使用 `org.json`
- **图片加载**：Coil（`io.coil-kt:coil-compose`）
- **构建**：Gradle Kotlin DSL（`build.gradle.kts`）+ Android Gradle Plugin
- **最低/目标/编译 SDK**：`minSdk 26` / `targetSdk 37` / `compileSdk 37`（Android 15）
- **Java 兼容性**：Java 11

---

## 项目结构

```
mirinda-ui/
├── app/
│   └── src/main/java/com/seesinlee/mirinda/
│       ├── MainActivity.kt          # 应用入口，创建并持载 ChatViewModel / ChatScreen
│       ├── MirindaApplication.kt    # 全局 Application，提供自定义 Coil ImageLoader（磁盘缓存）
│       ├── ChatScreen.kt            # 全部 Compose UI（聊天界面、设置、历史等）
│       ├── ChatViewModel.kt         # 业务逻辑层（消息、会话、模型、附件、流式状态）
│       ├── ChatApi.kt               # 网络层（SSE 解析、上传、登录、会话/消息拉取）
│       ├── ChatModels.kt            # 数据模型（ContentBlock / ChatMessage / ChatModel / Conversation）
│       ├── Markdown.kt              # Markdown 文本渲染组件
│       ├── FileTypes.kt             # 文件类型 / 后缀判断与图标
│       ├── AppColors.kt             # 全局主题配色
│       ├── TokenStore.kt            # 本地 token 缓存（SharedPreferences）
│       ├── QuickQueryActivity.kt    # 划词查询入口（透明 Activity，PROCESS_TEXT）
│       └── FloatingQueryService.kt  # 划词查询悬浮窗（前台服务 + WindowManager）
├── build.gradle.kts                 # 顶层构建配置
├── settings.gradle.kts              # 工程配置（rootProject.name = "mirinda"）
└── gradle.properties
```

---

## 架构说明

采用 MVVM 单向数据流，UI 通过观察 ViewModel 中的可观察状态自动刷新：

- **UI 层**：`ChatScreen.kt` 用 Compose 描述所有界面，从 `ChatViewModel` 读取 `State` / `StateList` 并派发用户意图。
- **ViewModel 层**：`ChatViewModel` 持有消息列表、待发送附件、模型列表、会话列表与登录态；`sendMessage()` 调用 `ChatApi.sendStream()` 收集 SSE 事件并更新状态。
- **网络层**：`ChatApi` 将 SSE 流解析为 `StreamEvent`（Reasoning / Token / ToolCall / File / Meta），以冷 `Flow` 暴露给上层。

**划词查询链路**：

1. 在任意 App 选中文字 → 系统工具栏出现本应用条目。
2. `QuickQueryActivity`（透明、无界面）取出文字，校验 `SYSTEM_ALERT_WINDOW` 权限。
3. 启动 `FloatingQueryService` 前台服务，用 `WindowManager`（`TYPE_APPLICATION_OVERLAY`）添加可拖拽悬浮窗。
4. 悬浮窗通过 `ChatApi.sendOnceStream()` 一次性流式查询（用完即弃，不落历史），支持关闭与「在 App 中继续」。

---

## 后端接口约定

所有请求均带鉴权头 `x-user-token`（值为本地 token）。流式接口返回 `text/event-stream`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/user` | 登录校验（HTTP 200 视为成功）。 |
| GET | `/user/heartbeat` | 心跳保活（200 视为在线）。 |
| GET | `/agent` | 拉取可用模型列表（`name` / `desc` / `hello`）。 |
| POST | `/agent/send` | 流式对话，SSE 事件：`reasoning` / `token` / `tool_call` / `file` / `meta`。 |
| POST | `/agent/send_once` | 一次性查询（悬浮窗用，用完即弃）。 |
| GET | `/agent/conversation` | 历史对话列表（`page` / `page_size`）。 |
| DELETE | `/agent/conversation` | 删除对话（`{"id": <serverId>}`）。 |
| GET | `/agent/message` | 某对话的消息记录（`conversation_id` / `page` / `page_size`，后端倒序返回）。 |
| POST | `/file` | 上传文件（multipart/form-data：`file` + `name`），返回 OBS key。 |
| GET | `/file/...` | 文件访问，地址形如 `{BASE_URL}/file/user_files/{token}/{key}`。 |

SSE 事件示例：

```
event:reasoning
data:思考中的文字

event:token
data:正式回复的文字

event:tool_call
data:[{"name":"...","type":"..."}]

event:file
data:{"filename":"x.jpg","download_url":"http://.../x.jpg","content_type":"image/jpeg"}

event:meta
data:{"conversation_id":7,"title":"..."}
```

---

## 快速开始

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 11+
- Android 设备或模拟器（minSdk 26，即 Android 8.0）

### 构建与运行

```bash
# 克隆仓库（如适用）后，在项目根目录执行：
./gradlew assembleDebug        # 构建 debug 包
# 或在 Android Studio 中直接点击 Run ▶ 运行 app 模块
```

> 首次运行需配置后端地址（见下方「关键配置」），否则所有网络请求会指向占位地址。

---

## 所需权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 访问后端服务。 |
| `SYSTEM_ALERT_WINDOW` | 划词查询系统级悬浮窗（需用户在系统设置中手动授予）。 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 承载悬浮窗的前台服务。 |
| `POST_NOTIFICATIONS` | 前台服务通知。 |

此外，`AndroidManifest.xml` 中设置了 `usesCleartextTraffic="true"`，允许与 HTTP（明文）后端通信。

---

## 关键配置

### 后端地址

网络层地址为占位值，使用前请在 `app/src/main/java/com/seesinlee/mirinda/ChatApi.kt` 中修改：

```kotlin
private const val BASE_URL = "http://example:80"   // 替换为你的后端地址
```

> 若后端使用 HTTPS，需同步确认 `AndroidManifest.xml` 的 `usesCleartextTraffic` 配置是否仍需保留。

### Token

- App 首次启动会在 `SharedPreferences` 中自动生成一个设备默认 token 并持久化。
- 可在设置页手动填写 / 替换 token（留空则重新生成默认 token）。

---

## License

内部项目，详情请咨询维护者。

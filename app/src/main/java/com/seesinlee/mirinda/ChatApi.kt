package com.seesinlee.mirinda

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络层：与后端 HTTP 接口交互。
 *
 * 职责：把输入框的文字 POST 给后端 /agent/send，并把后端返回的
 * SSE 流式响应解析成一个个增量事件（推理 reasoning / 正文 token）。
 *
 * 后端返回的是 text/event-stream，每个事件形如：
 *   event:reasoning
 *   data:思考中的文字
 *   <空行>
 *   event:token
 *   data:正式回复的文字
 *   <空行>
 *   event:tool_call
 *   data:工具调用提示（工具名 / 说明 / JSON）
 *   <空行>
 */
object ChatApi {

    /**
     * 后端地址。
     */
    private const val BASE_URL = "http://192.168.3.34:9090"

    /**
     * 由后端返回的 OBS key 拼出可访问的文件 URL：`{BASE_URL}/file/{key}`。
     * 上传成功后把本函数生成的 URL 作为 files 字段传给 /agent/send。
     */
    fun fileUrl(key: String): String = "$BASE_URL/file/$key"

    /**
     * 解析 SSE `file` 事件的 data，提取文件名与可访问地址（download_url）。
     *
     * 后端实际推送的是一段**嵌套 JSON**：
     *   {"type":"text","text":"{\"content_type\":\"...\",\"download_url\":\"http://.../x.md\",\"filename\":\"x.md\",...}"}
     * 外层 `text` 字段里又包了一层 JSON。本函数两层都尝试解析，
     * 兼容以下情况：
     *   - 标准嵌套结构：取内层 `filename` 与 `download_url`；
     *   - 单层 JSON（直接含 filename/download_url）；
     *   - 纯 URL 字符串（旧格式 / 历史消息）：回退为把整串当 URL，
     *     文件名用 [FileTypes.fileNameFromUrl] 从链接尾部取。
     *
     * @return 第一项为文件名，第二项为可访问地址（downloadUrl）；任一缺失时回退解析，都不应为空串以外的安全值。
     */
    fun parseFileEvent(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "" to ""

        var filename = ""
        var url = ""

        // 外层：{"type":"text","text":"<内层JSON字符串>"}
        val outer = runCatching { JSONObject(trimmed) }.getOrNull()
        val candidate = outer?.optString("text")?.takeIf { it.isNotBlank() } ?: trimmed

        // 内层（或本身就是 JSON）
        val inner = runCatching { JSONObject(candidate) }.getOrNull()
        if (inner != null) {
            filename = inner.optString("filename").orEmpty()
            // 优先 download_url，其次退而求其次 url / object_key 拼出的访问地址
            url = inner.optString("download_url").orEmpty()
                .ifBlank { inner.optString("url").orEmpty() }
        }

        // 没拿到 URL：说明不是 JSON，把整串当纯 URL 处理
        if (url.isBlank()) url = trimmed

        // 文件名兜底：从 URL 尾部截取
        if (filename.isBlank()) filename = FileTypes.fileNameFromUrl(url)

        return filename to url
    }

    /** 流式事件类型 */
    sealed class StreamEvent {
        data class Reasoning(val delta: String) : StreamEvent()  // 推理内容增量
        data class Token(val delta: String) : StreamEvent()      // 正文内容增量
        /** 工具调用提示：后端发起某个工具调用时推送，info 为提示内容（工具名/说明/JSON 等） */
        data class ToolCall(val info: String) : StreamEvent()
        /**
         * 文件事件：后端推送一个文件信息（嵌套 JSON，见 [parseFileEvent]），
         * UI 单独用"文件气泡"（图标 + 文件名）展示，点击以 downloadUrl 打开。
         * @param filename    文件名（如 helloworld.md），用于气泡展示与类型判断
         * @param downloadUrl 文件的真实可访问地址（download_url），点击时打开
         */
        data class File(val filename: String, val downloadUrl: String) : StreamEvent()
        /** 流最开始的元信息：带后端会话 id（新对话此时才产生）和标题 */
        data class Meta(val conversationId: String, val title: String) : StreamEvent()
    }

    /** 历史对话列表项（后端 /agent/conversation 返回） */
    data class ConversationBrief(
        val serverId: String,   // 后端会话 id（数字字符串）
        val title: String,
        val createdAt: String   // 后端返回的时间字符串
    )

    /** 单条历史消息（后端 /agent/message 返回） */
    data class MessageBrief(
        val id: String,
        val role: String,        // "user" / "assistant"
        val part: String,        // 文本内容
        val messageType: String  // question / reasoning / token / image / file
    )

    /** 登录结果 */
    data class LoginResult(val ok: Boolean, val httpCode: Int, val bizCode: Int, val message: String)

    /** 鉴权头名（后端从该 header 取 token）。非 private，供下载等场景复用。 */
    const val HEADER_TOKEN = "x-user-token"

    /** 给连接统一加上 token 请求头 */
    private fun HttpURLConnection.applyAuth() {
        setRequestProperty(HEADER_TOKEN, TokenStore.getToken())
    }

    /**
     * 上传文件：POST /file，使用 multipart/form-data。
     *
     * 表单字段：
     *   - file：文件流（二进制）
     *   - name：文件名称
     *
     * 后端成功时返回 { "code": ..., "data": "<obs key>", ... }，
     * 这里把 data（OBS key）取出返回；调用方再用 [fileUrl] 拼成可访问 URL。
     *
     * @param uri     待上传文件的 Uri（图片选择/拍照得到）
     * @param context 用于读取 Uri 的 ContentResolver（Application Context 即可）
     * @return 后端返回的 OBS key 字符串
     * @throws RuntimeException 读取失败 / 后端非 2xx / 未返回 key 时抛出
     */
    suspend fun uploadFile(uri: Uri, context: Context): String = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val fileName = queryDisplayName(cr, uri)
            ?: "upload_${System.currentTimeMillis()}.jpg"

        val conn = (URL("$BASE_URL/file").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            applyAuth()
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        val boundary = "----MirindaBoundary${System.currentTimeMillis()}"
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val crlf = "\r\n"
        val dash = "--"

        try {
            val input = cr.openInputStream(uri)
                ?: throw RuntimeException("无法读取图片文件：$uri")

            input.use { src ->
                conn.outputStream.use { out ->
                    val writer = out.bufferedWriter(Charsets.UTF_8)

                    // 字段：name
                    writer.write("$dash$boundary$crlf")
                    writer.write("Content-Disposition: form-data; name=\"name\"$crlf$crlf")
                    writer.write(fileName)
                    writer.write(crlf)
                    writer.flush()   // 先落盘文本部分，再写二进制

                    // 字段：file（二进制流）
                    writer.write("$dash$boundary$crlf")
                    writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$crlf")
                    writer.write("Content-Type: application/octet-stream$crlf$crlf")
                    writer.flush()
                    src.copyTo(out)   // 直接以字节方式拷贝，避免把图片当文本编码
                    out.flush()

                    // 结束边界
                    writer.write(crlf)
                    writer.write("$dash$boundary$dash$crlf")
                    writer.flush()
                }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw RuntimeException("上传文件失败，服务端返回 $code：$err")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val key = runCatching { JSONObject(body).optString("data") }.getOrNull().orEmpty()
            if (key.isBlank()) {
                throw RuntimeException("上传文件失败：后端未返回有效的文件 key（响应：$body）")
            }
            key
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 从 Uri 取文件显示名（供选择附件后在预览区展示、并据后缀判断图片/文件）。
     * 取不到时回退一个带时间戳的占位名。
     */
    fun displayNameOf(uri: Uri, context: Context): String =
        queryDisplayName(context.contentResolver, uri)
            ?: "file_${System.currentTimeMillis()}"

    /** 从 Uri 取文件显示名（无则回 null）。 */
    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
        return runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
        }.getOrNull()
    }

    /**
     * 登录校验：GET /user，带 x-user-token 头。
     * HTTP 200 视为登录成功；其它状态码视为失败（把后端返回的 code/message 带回去）。
     * 网络异常会向上抛出，由调用方处理。
     */
    suspend fun login(): LoginResult = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/user")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            applyAuth()
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()

            var bizCode = 0
            var message = ""
            runCatching {
                val o = JSONObject(body)
                bizCode = o.optInt("code")
                message = o.optString("message")
            }

            LoginResult(ok = code == HttpURLConnection.HTTP_OK, httpCode = code, bizCode = bizCode, message = message)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 心跳：GET /user/heartbeat，带 x-user-token 头。
     * HTTP 200 视为在线返回 true；网络异常/非 200 返回 false（不抛异常，便于轮询持续运行）。
     */
    suspend fun heartbeat(): Boolean = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/user/heartbeat")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            applyAuth()
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 拉取当前用户的历史对话列表：GET /agent/conversation?page=&page_size=（带 x-user-token 头）。
     * 返回 ConversationBrief 列表（后端 data.data 数组，字段 id/title/created_at）。
     */
    suspend fun fetchConversations(page: Int = 1, pageSize: Int = 10): List<ConversationBrief> =
        withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL/agent/conversation?page=$page&page_size=$pageSize")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                applyAuth()
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val err = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    throw RuntimeException("获取对话列表失败，服务端返回 $code：$err")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }

                val result = mutableListOf<ConversationBrief>()
                // 结构：{ "data": { "data": [ {id,title,created_at}, ... ], "total": .. }, "code": .. }
                val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        result.add(
                            ConversationBrief(
                                serverId = o.opt("id")?.toString().orEmpty(),
                                title = o.optString("title"),
                                createdAt = o.optString("created_at")
                            )
                        )
                    }
                }
                result
            } finally {
                conn.disconnect()
            }
        }

    /**
     * 删除一段历史对话：DELETE /agent/conversation，body {"id": <serverId>}（带 x-user-token 头）。
     * 后端成功返回 202。
     */
    suspend fun deleteConversation(serverId: String): Boolean = withContext(Dispatchers.IO) {
        val id = serverId.toIntOrNull() ?: return@withContext false
        val url = URL("$BASE_URL/agent/conversation")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            applyAuth()
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val payload = JSONObject().put("id", id).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            code in 200..299
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 拉取某段历史对话的消息记录：GET /agent/message?conversation_id=&page=&page_size=（带 x-user-token 头）。
     *
     * 后端按 created_at 倒序（新→旧）返回；这里**反转成正序**（旧→新）返回给调用方，
     * 直接用于按时间顺序渲染气泡。返回 MessageBrief 列表（data.data 数组，字段 id/role/part/message_type）。
     */
    suspend fun fetchMessages(
        conversationId: String,
        page: Int = 1,
        pageSize: Int = 200
    ): List<MessageBrief> = withContext(Dispatchers.IO) {
        val id = conversationId.toIntOrNull() ?: return@withContext emptyList()
        val url = URL("$BASE_URL/agent/message?conversation_id=$id&page=$page&page_size=$pageSize")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            applyAuth()
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw RuntimeException("获取对话消息失败，服务端返回 $code：$err")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }

            val result = mutableListOf<MessageBrief>()
            // 结构：{ "data": { "data": [ {id,role,part,message_type}, ... ], "total": .. }, "code": .. }
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    result.add(
                        MessageBrief(
                            id = o.opt("id")?.toString().orEmpty(),
                            role = o.optString("role"),
                            part = o.optString("part"),
                            messageType = o.optString("message_type")
                        )
                    )
                }
            }
            // 后端倒序返回，反转成时间正序
            result.reversed()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 拉取后端可用的模型列表（GET /agent）。
     *
     * 后端返回：
     *   {
     *     "data": [ { "name": "...", "desc": "...", "hello": "..." }, ... ],
     *     "message": "",
     *     "code": 10001
     *   }
     *
     * 在 Dispatchers.IO 上执行；失败时抛异常，由调用方兜底。
     */
    suspend fun fetchModels(): List<ChatModel> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/agent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            applyAuth()
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val err = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            conn.disconnect()
            throw RuntimeException("获取模型列表失败，服务端返回 $code：$err")
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                add(
                    ChatModel(
                        id = name,                 // 请求体的 "model" 用的就是它
                        name = name,
                        desc = o.optString("desc"),
                        hello = o.optString("hello")
                    )
                )
            }
        }
    }

    /**
     * 发送一条消息，返回一个增量事件的冷流（Flow）。
     * 网络 IO 在 Dispatchers.IO 上执行，收集方（ViewModel）在主线程更新 UI。
     *
     * @param message 用户输入的文字
     * @param model   模型 id（例如 "deepseek-flash"）
     * @param files   已上传文件的访问 URL 列表（形如 "{BASE_URL}/file/{key}"），无则空
     */
    fun sendStream(
        message: String,
        model: String,
        conversationId: Int = 0,
        files: List<String> = emptyList()
    ): Flow<StreamEvent> = flow {
        val url = URL("$BASE_URL/agent/send")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            applyAuth()
            connectTimeout = 15_000
            readTimeout = 60_000   // 两个 token 之间的最大等待；流式场景给足余量
        }

        // ---- 请求体：{"message": "...", "model": "...", "files": [...]} ----
        // conversation_id 为 0 表示新对话，后端会新建会话并在 meta 事件里回传真实 id。
        // token 由 applyAuth() 放在 x-user-token 请求头里，无需再放进请求体。
        val payload = JSONObject()
            .put("message", message)
            .put("model", model)
            .put("conversation_id", conversationId)
            .put("files", JSONArray(files))
            .toString()

        // try/finally 确保：无论正常结束、出错，还是"用户中断导致协程被取消"，
        // 都会 disconnect 断开底层连接（中断网络请求）。
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw RuntimeException("服务端返回 $code：$err")
            }

            // ---- 逐行读取并解析 SSE ----
            var eventName: String? = null
            val dataBuf = StringBuilder()
            var sawData = false   // 本事件内是否已出现过 data: 行（用于正确拼回换行）

            conn.inputStream.bufferedReader().use { reader ->
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break

                    if (line.isEmpty()) {
                        // 空行 = 一个完整事件结束，派发它
                        if (dataBuf.isNotEmpty() || eventName != null) {
                            val data = dataBuf.toString()
                            when (eventName) {
                                // emit 是挂起点：若此时协程已被取消，会在这里抛出
                                // CancellationException，从而跳到 finally 断开连接。
                                "reasoning" -> emit(StreamEvent.Reasoning(data))
                                "token"     -> emit(StreamEvent.Token(data))
                                // 工具调用提示：原样把 data 作为提示内容发出，UI 单独做一个气泡展示
                                "tool_call" -> emit(StreamEvent.ToolCall(data))
                                // 文件事件：data 为嵌套 JSON（见 parseFileEvent），
                                // 解析出文件名与可访问地址，UI 用文件气泡展示（始终排在正文之后）
                                "file"      -> {
                                    val (filename, downloadUrl) = parseFileEvent(data)
                                    emit(StreamEvent.File(filename = filename, downloadUrl = downloadUrl))
                                }
                                "meta"      -> {
                                    // data: {"conversation_id":7,"title":"..."}
                                    runCatching {
                                        val o = JSONObject(data)
                                        emit(
                                            StreamEvent.Meta(
                                                conversationId = o.opt("conversation_id")?.toString().orEmpty(),
                                                title = o.optString("title")
                                            )
                                        )
                                    }.getOrNull()
                                    Unit
                                }
                                // 其它事件（如没有 event 名）忽略
                            }
                        }
                        eventName = null
                        dataBuf.setLength(0)
                        sawData = false
                        continue
                    }

                    when {
                        line.startsWith("event:") -> {
                            eventName = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            // gin 的 sse 对多行内容会拆成多条 data: 行，这里用 \n 拼回去。
                            // 注意：必须用 sawData 判断，而不是 dataBuf.isNotEmpty()——
                            // 否则当 token 本身是纯换行/空行时（data: 行内容为空），换行会被整段吞掉。
                            if (sawData) dataBuf.append("\n")
                            dataBuf.append(line.removePrefix("data:"))
                            sawData = true
                        }
                        // 以 ":" 开头的是注释行；其它未知字段忽略
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 悬浮窗「划词查询」专用：POST /agent/send_once，流式返回。
     *
     * 与 [sendStream] 的区别：该接口**只产出 SSE 的 token 事件**（正文增量），
     * 不包含 reasoning / meta。因此这里直接把每个 token 的文本增量作为字符串发出，
     * 调用方拼接即可得到完整正文。
     *
     * 一次性查询：不传 model（后端有默认模型），也不带 conversation_id
     * （悬浮窗用完即弃，不落地到 App 内历史对话）。请求体仅含 message。
     *
     * @param message 用户划词选中的文字
     */
    fun sendOnceStream(message: String): Flow<String> = flow {
        val url = URL("$BASE_URL/agent/send_once")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            applyAuth()
            connectTimeout = 15_000
            readTimeout = 60_000   // 两个 token 之间的最大等待
        }

        val payload = JSONObject()
            .put("message", message)
            .toString()

        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw RuntimeException("服务端返回 $code：$err")
            }

            // ---- 逐行读取并解析 SSE，只保留 token 事件 ----
            var eventName: String? = null
            val dataBuf = StringBuilder()
            var sawData = false   // 本事件内是否已出现过 data: 行（用于正确拼回换行）

            conn.inputStream.bufferedReader().use { reader ->
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break

                    if (line.isEmpty()) {
                        // 空行 = 一个完整事件结束
                        if (eventName == "token" && dataBuf.isNotEmpty()) {
                            // emit 是挂起点：协程被取消会在此抛出 CancellationException -> finally 断连
                            emit(dataBuf.toString())
                        }
                        eventName = null
                        dataBuf.setLength(0)
                        sawData = false
                        continue
                    }

                    when {
                        line.startsWith("event:") -> {
                            eventName = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            // 用 sawData 而非 dataBuf.isNotEmpty()，才能正确还原纯换行/空行 token
                            if (sawData) dataBuf.append("\n")
                            dataBuf.append(line.removePrefix("data:"))
                            sawData = true
                        }
                        // 注释行 / 未知字段忽略
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

package com.seesinlee.mirinda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
 */
object ChatApi {

    /**
     * 后端地址。
     */
    private const val BASE_URL = "http://10.83.254.37:9090"

    /** 流式事件类型 */
    sealed class StreamEvent {
        data class Reasoning(val delta: String) : StreamEvent()  // 推理内容增量
        data class Token(val delta: String) : StreamEvent()      // 正文内容增量
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
        val messageType: String  // question / reasoning / token / image
    )

    /** 登录结果 */
    data class LoginResult(val ok: Boolean, val httpCode: Int, val bizCode: Int, val message: String)

    /** 鉴权头名（后端从该 header 取 token） */
    private const val HEADER_TOKEN = "x-user-token"

    /** 给连接统一加上 token 请求头 */
    private fun HttpURLConnection.applyAuth() {
        setRequestProperty(HEADER_TOKEN, TokenStore.getToken())
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
    suspend fun fetchConversations(page: Int = 1, pageSize: Int = 50): List<ConversationBrief> =
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
     */
    fun sendStream(message: String, model: String, conversationId: Int = 0): Flow<StreamEvent> = flow {
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

        // ---- 请求体：{"message": "...", "model": "..."} ----
        // conversation_id 为 0 表示新对话，后端会新建会话并在 meta 事件里回传真实 id。
        // token 由 applyAuth() 放在 x-user-token 请求头里，无需再放进请求体。
        val payload = JSONObject()
            .put("message", message)
            .put("model", model)
            .put("conversation_id", conversationId)
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
                        continue
                    }

                    when {
                        line.startsWith("event:") -> {
                            eventName = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            // gin 的 sse 对多行内容会拆成多条 data: 行，这里用 \n 拼回去
                            if (dataBuf.isNotEmpty()) dataBuf.append("\n")
                            dataBuf.append(line.removePrefix("data:"))
                        }
                        // 以 ":" 开头的是注释行；其它未知字段忽略
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

package com.seesinlee.mirinda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络层（相当于后端的 HTTP Client）。
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
    private const val BASE_URL = "http://10.83.254.39:9090"

    /** 流式事件类型 */
    sealed class StreamEvent {
        data class Reasoning(val delta: String) : StreamEvent()  // 推理内容增量
        data class Token(val delta: String) : StreamEvent()      // 正文内容增量
    }

    /**
     * 发送一条消息，返回一个增量事件的冷流（Flow）。
     * 网络 IO 在 Dispatchers.IO 上执行，收集方（ViewModel）在主线程更新 UI。
     *
     * @param message 用户输入的文字
     * @param model   模型 id（例如 "deepseek-flash"）
     */
    fun sendStream(message: String, model: String): Flow<StreamEvent> = flow {
        val url = URL("$BASE_URL/agent/send")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 15_000
            readTimeout = 60_000   // 两个 token 之间的最大等待；流式场景给足余量
        }

        // ---- 请求体：{"message": "...", "model": "..."} ----
        val payload = JSONObject()
            .put("message", message)
            .put("model", model)
            .toString()

        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val err = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            conn.disconnect()
            throw RuntimeException("服务端返回 $code：$err")
        }

        // ---- 逐行读取并解析 SSE ----
        var eventName: String? = null
        val dataBuf = StringBuilder()

        conn.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break

                if (line.isEmpty()) {
                    // 空行 = 一个完整事件结束，派发它
                    if (dataBuf.isNotEmpty() || eventName != null) {
                        val data = dataBuf.toString()
                        when (eventName) {
                            "reasoning" -> emit(StreamEvent.Reasoning(data))
                            "token"     -> emit(StreamEvent.Token(data))
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
        conn.disconnect()
    }.flowOn(Dispatchers.IO)
}

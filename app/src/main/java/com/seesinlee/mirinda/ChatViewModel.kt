package com.seesinlee.mirinda

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 业务逻辑层（相当于你后端的 Service 层）
 *
 * 它持有：
 *  - messages：当前对话的消息列表（UI 观察它，变了就自动刷新）
 *  - pendingImages：当前"待发送"的图片（显示在输入框上方的预览区）
 *  - availableModels / selectedModel：可选模型与当前选中的模型
 *  - conversations / currentConversationId：对话历史列表与当前所在对话
 *
 * sendMessage() 已接入后端 /agent/send（流式 SSE）。
 */
class ChatViewModel : ViewModel() {

    // ============ 消息相关 ============

    // mutableStateListOf：一个"可被 Compose 观察"的列表，增删会自动触发 UI 刷新
    val messages = mutableStateListOf<ChatMessage>()

    // 待发送的图片（输入框上方预览区的数据源）
    val pendingImages = mutableStateListOf<Uri>()

    // 是否正在等待回复（控制发送按钮禁用/loading）
    val isLoading = mutableStateOf(false)

    // ============ 模型相关 ============

    /** 可选模型列表（由后端提供） */
    val availableModels = mutableStateListOf<ChatModel>()

    /** 当前选中的模型 */
    val selectedModel = mutableStateOf<ChatModel?>(null)

    /** 欢迎语 */
    val welcomeText = mutableStateOf("")

    // ============ 对话历史相关 ============

    /** 对话历史列表（抽屉下半部分展示） */
    val conversations = mutableStateListOf<Conversation>()

    /** 当前所在对话的 id */
    val currentConversationId = mutableStateOf<String?>(null)

    /** 各对话对应的消息（内存保存） */
    private val conversationMessages = mutableMapOf<String, MutableList<ChatMessage>>()

    init {
        loadModels()
        loadConversations()
    }

    // ===================================================================
    //  模型
    // ===================================================================

    /**
     * 加载可选模型。
     *
     * 这里的 id 必须与后端 config.yaml 里的 model 名一致，
     * 因为请求体里的 "model" 字段用的就是它。后端目前支持 "deepseek-flash"。
     */
    private fun loadModels() {
        val models = listOf(
            ChatModel(id = "deepseek-flash", name = "DeepSeek Flash", desc = "深度求索 · 流式")
            // 以后后端支持更多模型时，在这里继续加（id 要与后端 config 对应）
        )
        availableModels.clear()
        availableModels.addAll(models)
        // 默认选中第一个
        selectedModel.value = availableModels.firstOrNull()

        welcomeText.value = "你好～ 输入文字后发送试试。\n左上角菜单可切换模型和历史对话。"
    }

    /** 生成一段"新对话"的初始消息：带上欢迎语 */
    private fun freshConversationMessages(): MutableList<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val w = welcomeText.value
        if (w.isNotBlank()) {
            list.add(
                ChatMessage(
                    id = newId(),
                    role = Role.ASSISTANT,
                    blocks = listOf(ContentBlock.Text(w))
                )
            )
        }
        return list
    }

    /** 选择模型 */
    fun selectModel(model: ChatModel) {
        selectedModel.value = model
    }

    // ===================================================================
    //  对话历史
    // ===================================================================

    private fun loadConversations() {
        val first = Conversation(id = newId(), title = "新对话")
        conversations.clear()
        conversations.add(first)
        currentConversationId.value = first.id

        conversationMessages[first.id] = freshConversationMessages()
        syncMessagesFromCurrent()
    }

    /** 切换到某段历史对话 */
    fun switchConversation(id: String) {
        if (id == currentConversationId.value) return
        saveMessagesToCurrent()          // 先存好当前对话
        currentConversationId.value = id
        syncMessagesFromCurrent()        // 再加载目标对话
        pendingImages.clear()
    }

    /** 新建对话 */
    fun newConversation() {
        saveMessagesToCurrent()
        val conv = Conversation(id = newId(), title = "新对话")
        conversations.add(0, conv)       // 放到列表最上面
        currentConversationId.value = conv.id
        conversationMessages[conv.id] = freshConversationMessages()
        pendingImages.clear()
        syncMessagesFromCurrent()
    }

    /** 删除某段历史对话 */
    fun deleteConversation(id: String) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        val wasCurrent = currentConversationId.value == id
        conversations.removeAt(idx)
        conversationMessages.remove(id)

        if (wasCurrent) {
            val next = conversations.firstOrNull()
            if (next != null) {
                currentConversationId.value = next.id
            } else {
                val conv = Conversation(id = newId(), title = "新对话")
                conversations.add(conv)
                currentConversationId.value = conv.id
                conversationMessages[conv.id] = freshConversationMessages()
            }
            pendingImages.clear()
            syncMessagesFromCurrent()
        }
    }

    /** 把内存里当前对话的消息刷进 messages（供 UI 显示） */
    private fun syncMessagesFromCurrent() {
        val id = currentConversationId.value ?: return
        val list = conversationMessages.getOrPut(id) { mutableListOf() }
        messages.clear()
        messages.addAll(list)
    }

    /** 把当前 messages 存回内存对应的对话 */
    private fun saveMessagesToCurrent() {
        val id = currentConversationId.value ?: return
        conversationMessages[id] = messages.toMutableList()
    }

    // ===================================================================
    //  图片
    // ===================================================================

    fun addPendingImage(uri: Uri) {
        pendingImages.add(uri)
    }

    fun removePendingImage(uri: Uri) {
        pendingImages.remove(uri)
    }

    // ===================================================================
    //  发送消息（已接入后端 /agent/send 流式 SSE）
    // ===================================================================

    /**
     * 发送消息
     * @param text 输入框里的文字
     *
     * 流程：
     * 1. 把"文字 + 待发送图片"组装成一条用户消息，加入列表
     * 2. 先插入一条"空的助手消息"占位（isStreaming=true）
     * 3. 调后端 /agent/send，按 SSE 增量把 token 追加到该助手消息里
     *
     * 注意：后端请求体只有 {message, model}，暂不支持图片，
     * 因此图片只在本地气泡里预览，不会上传。
     */
    fun sendMessage(text: String) {
        if (text.isBlank() && pendingImages.isEmpty()) return
        if (isLoading.value) return  // 正在请求时不重复发送

        // ---- 组装用户消息：先放文字（若有），再放所有图片 ----
        val blocks = mutableListOf<ContentBlock>()
        if (text.isNotBlank()) blocks.add(ContentBlock.Text(text))
        pendingImages.forEach { blocks.add(ContentBlock.Image(localUri = it)) }

        messages.add(ChatMessage(id = newId(), role = Role.USER, blocks = blocks))

        // 用第一条用户文字更新对话标题
        maybeUpdateTitle(text)

        // 清空待发送图片
        pendingImages.clear()

        val modelId = selectedModel.value?.id ?: "deepseek-flash"
        val userText = text

        // ---- 插入助手占位消息，用于流式追加 ----
        val assistantId = newId()
        messages.add(
            ChatMessage(
                id = assistantId,
                role = Role.ASSISTANT,
                blocks = listOf(ContentBlock.Text("")),
                isStreaming = true
            )
        )
        isLoading.value = true

        // ---- 发起流式请求 ----
        val reasoning = StringBuilder()
        val content = StringBuilder()

        viewModelScope.launch {
            ChatApi.sendStream(userText, modelId)
                .catch { e ->
                    // 出错：把错误显示在助手气泡里
                    val msg = "请求失败：${e.message ?: e.toString()}"
                    updateAssistant(assistantId, reasoning.toString(), msg, streaming = false)
                }
                .onCompletion {
                    isLoading.value = false
                    // 结束时取消流式标记，并存档
                    val idx = messages.indexOfFirst { it.id == assistantId }
                    if (idx >= 0 && messages[idx].isStreaming) {
                        messages[idx] = messages[idx].copy(isStreaming = false)
                    }
                    saveMessagesToCurrent()
                }
                .collect { ev ->
                    when (ev) {
                        is ChatApi.StreamEvent.Reasoning -> {
                            reasoning.append(ev.delta)
                            updateAssistant(assistantId, reasoning.toString(), content.toString(), streaming = true)
                        }
                        is ChatApi.StreamEvent.Token -> {
                            content.append(ev.delta)
                            updateAssistant(assistantId, reasoning.toString(), content.toString(), streaming = true)
                        }
                    }
                }
        }
    }

    /**
     * 更新助手消息的内容。
     * 把"思考"和"回复"拆成独立的块：
     *   - 有推理内容 -> 放一个 Reasoning 块（UI 用可折叠的"深度思考"面板展示）
     *   - 有正文内容 -> 放一个 Text 块（正式回复，展示在思考面板下方）
     * 这样就实现了"先思考、再回复"：思考阶段只有 Reasoning 块，
     * 正文一开始出现，Reasoning 面板会自动折叠（由 UI 根据 isStreaming 控制）。
     */
    private fun updateAssistant(id: String, reasoning: String, content: String, streaming: Boolean) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return

        val blocks = buildList {
            if (reasoning.isNotEmpty()) add(ContentBlock.Reasoning(reasoning))
            if (content.isNotEmpty()) add(ContentBlock.Text(content))
        }.ifEmpty { listOf(ContentBlock.Text("")) }

        messages[idx] = messages[idx].copy(blocks = blocks, isStreaming = streaming)
    }

    /** 用第一条用户文字作为对话标题 */
    private fun maybeUpdateTitle(text: String) {
        val id = currentConversationId.value ?: return
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        val conv = conversations[idx]
        if (conv.title == "新对话" && text.isNotBlank()) {
            val title = if (text.length > 16) text.take(16) + "…" else text
            conversations[idx] = conv.copy(title = title, updated = System.currentTimeMillis())
        }
    }

    private fun newId() = UUID.randomUUID().toString()
}

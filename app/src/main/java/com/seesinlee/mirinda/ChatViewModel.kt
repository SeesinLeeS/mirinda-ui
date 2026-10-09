package com.seesinlee.mirinda

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 业务逻辑层（相当于你后端的 Service 层）
 *
 * 它持有：
 *  - messages：当前对话的消息列表（UI 观察它，变了就自动刷新）
 *  - pendingImages：当前"待发送"的图片（显示在输入框上方的预览区）
 *  - availableModels / selectedModel：可选模型（由后端提供）与当前选中的模型
 *  - conversations / currentConversationId：对话历史列表与当前所在对话
 *
 * Level 2 接后端时，主要改三处（都已用【后端接入点】标出）：
 *  1. loadModels()  —— 把假模型列表换成从后端拉取
 *  2. loadConversations() —— 把假历史换成从后端拉取
 *  3. sendMessage() —— 把"假回复"换成真正调后端 + 流式接收
 * UI 层基本不用动。
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

    /** 欢迎语（由后端"模型列表接口"一并返回） */
    val welcomeText = mutableStateOf("")

    // ============ 对话历史相关 ============

    /** 对话历史列表（抽屉下半部分展示） */
    val conversations = mutableStateListOf<Conversation>()

    /** 当前所在对话的 id */
    val currentConversationId = mutableStateOf<String?>(null)

    /** 各对话对应的消息（内存保存；Level 2 可改成从后端按需加载） */
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
     * 【后端接入点】Level 2 把下面这段假数据换成真正的后端请求，例如：
     *   viewModelScope.launch {
     *       val list = api.getModels()            // Retrofit 调你的后端
     *       availableModels.clear()
     *       availableModels.addAll(list)
     *       selectedModel.value = availableModels.firstOrNull()
     *   }
     */
    private fun loadModels() {
        val mock = listOf(
            ChatModel(id = "gpt-4o", name = "GPT-4o", desc = "通用 · 多模态"),
            ChatModel(id = "gpt-4o-mini", name = "GPT-4o mini", desc = "轻量 · 快速"),
            ChatModel(id = "qwen-max", name = "通义千问 Max", desc = "中文友好"),
            ChatModel(id = "claude-3.5", name = "Claude 3.5", desc = "长文本 · 推理")
        )
        availableModels.clear()
        availableModels.addAll(mock)
        // 默认选中第一个
        selectedModel.value = availableModels.firstOrNull()

        // 欢迎语也由"模型列表接口"返回（Level 2 换成后端返回值，例如 resp.welcome）
        welcomeText.value = "你好～ 点右侧 + 可以插入图片，输入文字后发送试试。\n左上角菜单可切换模型和历史对话。"
    }

    /** 生成一段"新对话"的初始消息：带上欢迎语（欢迎语来自模型列表接口） */
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

    /**
     * 加载对话历史。
     *
     * 【后端接入点】Level 2 换成从后端拉取历史列表（以及每段对话的消息）。
     */
    private fun loadConversations() {
        // Level 1：开局只有一个新对话
        val first = Conversation(id = newId(), title = "新对话")
        conversations.clear()
        conversations.add(first)
        currentConversationId.value = first.id

        // 新对话带上欢迎语（欢迎语来自模型列表接口）
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
                // 一条都不剩，自动建一个空的新对话
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

    /** 用户选了一张图 -> 加入待发送预览区 */
    fun addPendingImage(uri: Uri) {
        pendingImages.add(uri)
    }

    /** 从待发送预览区移除某张图 */
    fun removePendingImage(uri: Uri) {
        pendingImages.remove(uri)
    }

    // ===================================================================
    //  发送消息
    // ===================================================================

    /**
     * 发送消息
     * @param text 输入框里的文字
     *
     * 流程：
     * 1. 把"文字 + 待发送图片"组装成一条用户消息，加入列表
     * 2. 清空待发送图片和输入框
     * 3.（Level 1）用假数据模拟助手 1 秒后回复
     *    （Level 2 这里换成真正调后端，记得带上 selectedModel.value?.id）
     */
    fun sendMessage(text: String) {
        if (text.isBlank() && pendingImages.isEmpty()) return

        // ---- 组装用户消息的内容块：先放文字（若有），再放所有图片 ----
        val blocks = mutableListOf<ContentBlock>()
        if (text.isNotBlank()) blocks.add(ContentBlock.Text(text))
        pendingImages.forEach { blocks.add(ContentBlock.Image(localUri = it)) }

        messages.add(ChatMessage(id = newId(), role = Role.USER, blocks = blocks))

        // 如果是对话里的第一条用户消息，用它更新一下该对话标题
        maybeUpdateTitle(text)

        // 清空待发送区
        val sentImageCount = pendingImages.size
        pendingImages.clear()

        // ---- 模拟助手回复（Level 1 假数据） ----
        isLoading.value = true
        viewModelScope.launch {
            delay(1000) // 假装在"思考"
            val modelName = selectedModel.value?.name ?: "未选择模型"
            val reply = buildString {
                append("【假回复·$modelName】我收到了你的消息")
                if (text.isNotBlank()) append("：\"$text\"")
                if (sentImageCount > 0) append("，以及 $sentImageCount 张图片")
                append("。\n\n（Level 2 接上后端后，这里会显示模型的真实回复）")
            }
            messages.add(
                ChatMessage(
                    id = newId(),
                    role = Role.ASSISTANT,
                    blocks = listOf(ContentBlock.Text(reply))
                )
            )
            isLoading.value = false
            saveMessagesToCurrent()
        }
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

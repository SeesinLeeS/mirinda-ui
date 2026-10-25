package com.seesinlee.mirinda

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 业务逻辑层（Service）
 *
 * 它持有：
 *  - messages：当前对话的消息列表（UI 观察它，变了就自动刷新）
 *  - pendingAttachments：当前"待发送"的附件（图片/文件，显示在输入框上方的预览区）
 *  - availableModels / selectedModel：可选模型与当前选中的模型
 *  - conversations / currentConversationId：对话历史列表与当前所在对话
 *
 * sendMessage() 已接入后端 /agent/send（流式 SSE）。
 */
/**
 * 待发送附件：选择后立即上传，[fileUrl] 为上传成功后后端返回的访问地址。
 * 附件可以是图片，也可以是 xlsx / docx / pdf 等普通文件，由 [isImage] 区分展示。
 */
data class PendingAttachment(
    val uri: Uri,
    val fileName: String,          // 文件名（含后缀），用于类型判断与预览展示
    val isImage: Boolean,          // 是否图片：决定预览用缩略图还是文件卡片
    val fileUrl: String? = null,   // 上传成功后的访问 URL；未上传完/失败为 null
    val uploading: Boolean = false,
    val failed: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ============ 消息相关 ============

    // mutableStateListOf：一个"可被 Compose 观察"的列表，增删会自动触发 UI 刷新
    val messages = mutableStateListOf<ChatMessage>()

    // 待发送的附件（输入框上方预览区的数据源）：选择后立即上传，fileUrl 为访问地址。
    // 既可以是图片，也可以是 xlsx / docx / pdf 等普通文件。
    val pendingAttachments = mutableStateListOf<PendingAttachment>()

    // 是否正在等待回复（控制发送按钮：loading 时变为"终止"按钮）
    val isLoading = mutableStateOf(false)

    // 当前这次流式请求的协程任务，用于"中断生成"时取消它
    private var sendJob: Job? = null

    // ============ 模型相关 ============

    /** 可选模型列表（由后端提供） */
    val availableModels = mutableStateListOf<ChatModel>()

    /** 当前选中的模型 */
    val selectedModel = mutableStateOf<ChatModel?>(null)

    /** 欢迎语 */
    val welcomeText = mutableStateOf("")

    /** 模型加载错误信息（非空时 UI 顶部横幅提示；加载成功时置空） */
    val errorMessage = mutableStateOf<String?>(null)

    // ============ 登录 / Token 相关 ============

    /** 登录状态 */
    enum class LoginState { IDLE, LOADING, SUCCESS, FAILED }

    /** 当前登录状态 */
    val loginState = mutableStateOf(LoginState.IDLE)

    /** 登录失败时的提示信息 */
    val loginMessage = mutableStateOf("")

    /** 当前 token（设置页展示/编辑；来源于本地缓存 TokenStore） */
    val token = mutableStateOf("")

    /** 心跳轮询协程（登录成功后启动，每 1s 请求一次 /user/heartbeat） */
    private var heartbeatJob: Job? = null

    // ============ 对话历史相关 ============

    /** 对话历史列表（抽屉下半部分展示） */
    val conversations = mutableStateListOf<Conversation>()

    /** 当前所在对话的 id */
    val currentConversationId = mutableStateOf<String?>(null)

    /** 各对话对应的消息（内存保存） */
    private val conversationMessages = mutableMapOf<String, MutableList<ChatMessage>>()

    /** 已从后端加载过历史消息（已拉过首页）的本地对话 id 集合 */
    private val loadedServerConversations = mutableSetOf<String>()

    /** 每个本地对话已加载到的历史页码（page=1 为最新一页，越大越早） */
    private val historyPage = mutableMapOf<String, Int>()

    /** 每个本地对话是否还有更早的历史消息可加载 */
    private val historyHasMore = mutableMapOf<String, Boolean>()

    /** 当前对话是否还有更早历史可加载（UI 据此决定是否在顶部触发加载/显示菊花） */
    val hasMoreHistory = mutableStateOf(false)

    /** 是否正在加载更早的历史消息（UI 顶部显示加载指示） */
    val isLoadingMore = mutableStateOf(false)

    /** 对话历史【列表】分页：当前已加载到的页码（page=1 为第一页） */
    private var conversationListPage = 1

    /** 对话历史【列表】是否还有下一页可加载（UI 滑到底部据此决定是否加载更多） */
    val hasMoreConversations = mutableStateOf(false)

    /** 是否正在加载对话历史列表的下一页（UI 底部显示加载指示） */
    val isLoadingMoreConversations = mutableStateOf(false)

    /** 对话历史列表分页大小 */
    private val CONVERSATION_PAGE_SIZE = 10

    /**
     * “滚动到最新一条底部”的一次性信号：每自增一次，UI 就把列表定位到最后。
     * 用于：进入/切换会话、历史首页加载完成等“应当定位到最新消息底部”的时机。
     */
    val scrollToBottomSignal = mutableStateOf(0)
    private fun requestScrollToBottom() { scrollToBottomSignal.value++ }

    /** 加载更早历史的协程，切换会话时取消 */
    private var loadMoreJob: Job? = null

    /**
     * 历史消息分页大小：后端每轮问答固定存 3 行（question/reasoning/token），
     * 取 3 的倍数可让分页边界正好落在整轮之间，避免把一轮问答跨页截断。
     */
    private val HISTORY_PAGE_SIZE = 9

    init {
        // 注意：获取模型列表的网络请求不在这里触发，
        // 而是由 App 启动入口 MainActivity 显式调用 loadModels()。
        loadConversations()
    }

    // ===================================================================
    //  模型
    // ===================================================================

    /**
     * 加载可选模型：通过 GET /agent 从后端动态获取。
     *
     * 后端返回的每个模型含 name / desc / hello，
     * 其中 name 既作为列表展示名，也作为发送请求时的 "model" 字段值。
     * 网络请求是异步的：先放一个占位默认值保证界面不空，
     * 拉到真实列表后再覆盖，并用选中模型的 hello 作为欢迎语。
     *
     * 由 App 启动入口（MainActivity.onCreate）显式调用。
     */
    fun loadModels() {
        viewModelScope.launch {
            try {
                val models = ChatApi.fetchModels()
                if (models.isEmpty()) {
                    throw RuntimeException("后端返回的模型列表为空")
                }
                // 记住重复调用（如每次打开菜单刷新）前的选中项，避免被覆盖
                val prevId = selectedModel.value?.id
                availableModels.clear()
                availableModels.addAll(models)
                val keep = models.firstOrNull { it.id == prevId }
                if (keep != null) {
                    // 用户此前已选模型仍然可用：保持不变，不打断当前欢迎语/会话
                    selectedModel.value = keep
                } else {
                    // 首次加载或原选中项已下线：回落到第一个并刷新欢迎语
                    selectedModel.value = models.first()
                    welcomeText.value = models.first().hello
                    refreshWelcomeMessage()
                }
                errorMessage.value = null
            } catch (e: Exception) {
                // 不做兜底：直接把错误暴露给用户
                errorMessage.value = "加载模型失败：${e.message}"
            }
        }
    }

    /**
     * 用最新的 welcomeText 刷新"尚未开始聊天"的对话里的欢迎语。
     * 只替换那些仅含一条 assistant 欢迎消息的对话，避免动到已有的聊天记录。
     */
    private fun refreshWelcomeMessage() {
        val w = welcomeText.value
        if (w.isBlank()) return

        // "尚未开始聊天"的对话：空列表（启动时 welcomeText 还没就绪而留空），
        // 或仅含一条 assistant 欢迎消息。两种都要刷新/补插欢迎语。
        fun isFreshWelcome(list: List<ChatMessage>): Boolean =
            list.isEmpty() || (list.size == 1 && list[0].role == Role.ASSISTANT)

        fun applyTo(list: MutableList<ChatMessage>) {
            if (list.isEmpty()) {
                list.add(
                    ChatMessage(
                        id = newId(),
                        role = Role.ASSISTANT,
                        blocks = listOf(ContentBlock.Text(w))
                    )
                )
            } else {
                list[0] = list[0].copy(blocks = listOf(ContentBlock.Text(w)))
            }
        }

        // 内存里各对话
        conversationMessages.forEach { (_, list) ->
            if (isFreshWelcome(list)) applyTo(list)
        }
        // 当前正在显示的 messages
        if (isFreshWelcome(messages)) applyTo(messages)
    }

    // ===================================================================
    //  登录 / Token
    // ===================================================================

    /**
     * 从本地缓存加载 token（若从未设置，TokenStore 会自动生成一个默认 token）。
     * 由 App 启动入口调用。
     */
    fun loadToken() {
        token.value = TokenStore.getToken()
    }

    /**
     * 向后端 GET /user 做登录校验（带 x-user-token 头）。
     * HTTP 200 即登录成功。由 App 启动入口在 loadToken 之后调用。
     */
    fun login() {
        loginState.value = LoginState.LOADING
        loginMessage.value = ""
        viewModelScope.launch {
            try {
                val r = ChatApi.login()
                if (r.ok) {
                    loginState.value = LoginState.SUCCESS
                    loginMessage.value = ""
                } else {
                    loginState.value = LoginState.FAILED
                    loginMessage.value = "登录失败（HTTP ${r.httpCode}，code=${r.bizCode}）${r.message}"
                }
            } catch (e: Exception) {
                loginState.value = LoginState.FAILED
                loginMessage.value = "登录失败：${e.message}"
            } finally {
                // 无论登录成功还是失败，都要保证心跳在跑：
                // 失败时靠心跳持续探测，等网络恢复（心跳从非 200 变 200）后自动重连。
                startHeartbeat()
            }
        }
    }

    /**
     * 保存用户在设置页填写的 token，并重新登录。
     * 传空串则由 TokenStore 重新生成一个默认 token。
     */
    fun saveToken(newToken: String) {
        token.value = TokenStore.setToken(newToken)
        login()
        // 保存并登录后，用新 token 刷新一次历史会话列表
        refreshConversationsFromServer()
    }

    /**
     * 启动心跳轮询：每 1s 请求一次 /user/heartbeat，根据结果刷新登录状态小圆点。
     * 通 → SUCCESS，不通 → FAILED；轮询不会因失败（含登录失败）而停止，
     * 便于网络恢复后自动变回在线，并在“重新连上”的瞬间自动重连拉数据。
     *
     * 关键：当心跳结果从“非 200（断开）”跳变为“200（连上）”时，
     * 视为重新连接成功，自动触发一次 login + 拉模型列表 + 拉历史对话列表。
     */
    private fun startHeartbeat() {
        // 已在运行则不重复启动：避免重连时 login() 再次调用本方法把正在跑的心跳取消掉
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = viewModelScope.launch {
            // 记录上一次心跳是否连通，用于识别“断开 -> 连上”的跳变；初始为 null（未知）
            var prevOk: Boolean? = null
            while (isActive) {
                val ok = ChatApi.heartbeat()

                // 从“非 200”变为“200”（重新连接上）：重新登录 + 刷新模型列表 + 刷新历史对话列表
                if (prevOk == false && ok) {
                    login()
                    loadModels()
                    refreshConversationsFromServer()
                }

                // 用心跳结果驱动登录状态小圆点；登录流程进行中（LOADING）时让位给 login()，避免状态抖动
                if (loginState.value != LoginState.LOADING) {
                    loginState.value = if (ok) LoginState.SUCCESS else LoginState.FAILED
                    if (ok) {
                        loginMessage.value = ""
                    } else if (loginMessage.value.isBlank()) {
                        loginMessage.value = "心跳失败，连接已断开"
                    }
                }

                prevOk = ok
                delay(1000)
            }
        }
    }

    /** 停止心跳轮询。 */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
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

    /** 选择模型（同步更新欢迎语为该模型的 hello） */
    fun selectModel(model: ChatModel) {
        selectedModel.value = model
        model.hello.takeIf { it.isNotBlank() }?.let { welcomeText.value = it }
    }

    // ===================================================================
    //  对话历史
    // ===================================================================

    private fun loadConversations() {
        // 本地先放一个"新对话"草稿作为当前会话（serverId 为空，发第一条消息后由 meta 回填）
        val first = Conversation(id = newId(), serverId = "", title = "新对话")
        conversations.clear()
        conversations.add(first)
        currentConversationId.value = first.id

        conversationMessages[first.id] = freshConversationMessages()
        syncMessagesFromCurrent()

        // 异步从后端拉取历史对话列表，合并到列表中
        refreshConversationsFromServer()
    }

    /**
     * 从后端拉取历史对话列表并重建 conversations：
     *  - 唯一的“新对话”草稿（serverId 为空）始终置顶，且**有且仅有一个**；
     *  - 远程会话排在草稿之后，并复用已有本地条目（保留其本地 id 与已加载的消息缓存）；
     *  - 已存在的更新 title / createdAt，不存在的分配一个新的本地 id。
     */
    fun refreshConversationsFromServer() {
        viewModelScope.launch {
            try {
                val remote = ChatApi.fetchConversations(page = 1, pageSize = CONVERSATION_PAGE_SIZE)

                // 1) 取唯一草稿：优先当前会话正用的草稿，其次列表里第一个草稿，都没有则新建一个
                val draft = conversations.firstOrNull { it.id == currentConversationId.value && it.serverId.isBlank() }
                    ?: conversations.firstOrNull { it.serverId.isBlank() }
                    ?: Conversation(id = newId(), serverId = "", title = "新对话").also {
                        conversationMessages[it.id] = freshConversationMessages()
                    }

                // 2) 重建列表：草稿置顶 + 远程会话（复用已有本地条目，保留 local id 与消息缓存）
                val rebuilt = mutableListOf(draft)
                remote.forEach { b ->
                    if (b.serverId.isBlank()) return@forEach
                    val existing = conversations.firstOrNull { it.serverId == b.serverId }
                    rebuilt.add(
                        existing?.copy(title = b.title, createdAt = b.createdAt)
                            ?: Conversation(id = newId(), serverId = b.serverId, title = b.title, createdAt = b.createdAt)
                    )
                }

                conversations.clear()
                conversations.addAll(rebuilt)

                // 刷新即回到第一页：记录页码，并据“本页是否满页”判断还有没有下一页
                conversationListPage = 1
                hasMoreConversations.value = remote.size >= CONVERSATION_PAGE_SIZE

                // 当前会话若在重建后已不存在（例如多余草稿被合并清理），切回保留的草稿
                if (conversations.none { it.id == currentConversationId.value }) {
                    currentConversationId.value = draft.id
                    syncMessagesFromCurrent()
                }
            } catch (e: Exception) {
                errorMessage.value = "加载历史对话失败：${e.message}"
            }
        }
    }

    /**
     * 加载对话历史【列表】的下一页，并【追加】到现有列表末尾。
     *  - 由抽屉里的列表滑到底部附近时触发；
     *  - 复用已有本地条目（保留其 local id 与消息缓存），按 serverId 去重；
     *  - 草稿（serverId 为空）始终留在首位，新增远程会话只往末尾追加。
     */
    fun loadMoreConversations() {
        if (isLoadingMoreConversations.value || !hasMoreConversations.value) return
        isLoadingMoreConversations.value = true
        viewModelScope.launch {
            try {
                val nextPage = conversationListPage + 1
                val remote = ChatApi.fetchConversations(page = nextPage, pageSize = CONVERSATION_PAGE_SIZE)

                remote.forEach { b ->
                    if (b.serverId.isBlank()) return@forEach
                    val idx = conversations.indexOfFirst { it.serverId == b.serverId }
                    if (idx >= 0) {
                        // 已存在：更新 title / createdAt，保留 local id 与消息缓存
                        conversations[idx] = conversations[idx].copy(title = b.title, createdAt = b.createdAt)
                    } else {
                        conversations.add(
                            Conversation(id = newId(), serverId = b.serverId, title = b.title, createdAt = b.createdAt)
                        )
                    }
                }

                conversationListPage = nextPage
                hasMoreConversations.value = remote.size >= CONVERSATION_PAGE_SIZE
            } catch (e: Exception) {
                errorMessage.value = "加载更多历史对话失败：${e.message}"
            } finally {
                isLoadingMoreConversations.value = false
            }
        }
    }

    /** 切换到某段历史对话 */
    fun switchConversation(id: String) {
        if (id == currentConversationId.value) return
        loadMoreJob?.cancel()            // 取消上一个会话未完成的“加载更早”
        isLoadingMore.value = false
        saveMessagesToCurrent()          // 先存好当前对话
        currentConversationId.value = id
        syncMessagesFromCurrent()        // 再加载目标对话（内存里的）
        pendingAttachments.clear()
        // 恢复该会话的“是否还有更早”标记（已加载过的会话直接用缓存）
        hasMoreHistory.value = historyHasMore[id] ?: false
        requestScrollToBottom()          // 进入会话：先按内存内容定位到底部
        maybeLoadServerMessages(id)      // 已入库对话：首次进入时从后端拉取最新一页
    }

    /**
     * 首次进入某段已入库对话时，从后端拉取**最新一页**消息（page=1）。
     *  - 本地草稿（serverId 为空）：无需拉取；
     *  - 已加载过 / 内存里已有真实聊天记录：跳过，避免覆盖正在进行的会话。
     * 更早的历史由 loadOlderMessages()（上滑触发）按页加载。
     */
    private fun maybeLoadServerMessages(localId: String) {
        val conv = conversations.firstOrNull { it.id == localId } ?: return
        if (conv.serverId.isBlank()) { hasMoreHistory.value = false; return }
        if (loadedServerConversations.contains(localId)) return
        // 内存里已有实质聊天记录（不只是欢迎语）就不覆盖
        val cached = conversationMessages[localId]
        if (cached != null && cached.size > 1) {
            loadedServerConversations.add(localId)
            hasMoreHistory.value = false
            return
        }

        loadedServerConversations.add(localId)   // 先占位，防止重复并发请求
        historyPage[localId] = 1
        viewModelScope.launch {
            try {
                val briefs = ChatApi.fetchMessages(conv.serverId, page = 1, pageSize = HISTORY_PAGE_SIZE)
                val more = briefs.size >= HISTORY_PAGE_SIZE
                historyHasMore[localId] = more
                val msgs = briefsToMessages(briefs)
                if (msgs.isNotEmpty()) {
                    conversationMessages[localId] = msgs.toMutableList()
                }
                if (currentConversationId.value == localId) {
                    if (msgs.isNotEmpty()) {
                        messages.clear()
                        messages.addAll(msgs)
                        requestScrollToBottom()   // 首页历史就绪：定位到最新一条底部
                    }
                    hasMoreHistory.value = more
                }
            } catch (e: Exception) {
                loadedServerConversations.remove(localId)   // 失败允许下次重试
                errorMessage.value = "加载对话消息失败：${e.message}"
            }
        }
    }

    /**
     * 上滑加载更早的历史消息（当前对话的下一页），把结果**拼接到列表顶部**。
     * 消息 id 由后端 id 派生（srv- 前缀），prepend 后 LazyColumn 会靠 key 锚定，
     * 滚动位置不跳。无更早 / 正在加载 / 非入库对话时直接返回。
     */
    fun loadOlderMessages() {
        val localId = currentConversationId.value ?: return
        val conv = conversations.firstOrNull { it.id == localId } ?: return
        if (conv.serverId.isBlank()) return
        if (historyHasMore[localId] != true) return
        if (isLoadingMore.value) return

        val nextPage = (historyPage[localId] ?: 1) + 1
        isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch {
            try {
                val older = ChatApi.fetchMessages(conv.serverId, page = nextPage, pageSize = HISTORY_PAGE_SIZE)
                val more = older.size >= HISTORY_PAGE_SIZE
                historyHasMore[localId] = more
                if (older.isNotEmpty()) {
                    historyPage[localId] = nextPage
                    val olderMsgs = briefsToMessages(older)
                    // 拼到内存与 UI 列表最前面（更早的在上）
                    val mem = conversationMessages.getOrPut(localId) { mutableListOf() }
                    mem.addAll(0, olderMsgs)
                    if (currentConversationId.value == localId) {
                        messages.addAll(0, olderMsgs)
                    }
                }
                if (currentConversationId.value == localId) hasMoreHistory.value = more
            } catch (e: Exception) {
                errorMessage.value = "加载更早消息失败：${e.message}"
            } finally {
                isLoadingMore.value = false
            }
        }
    }

    /**
     * 把后端返回的扁平消息列表（已按时间正序）转换成 UI 的 ChatMessage 列表。
     *
     * 后端每一轮问答存 3 条：user(question) + assistant(reasoning) + assistant(token)，
     * 这里把相邻的 assistant 推理/正文合并成**一条**助手气泡：
     *   - reasoning 非空 -> Reasoning 块（可折叠"深度思考"）
     *   - token 非空     -> Text 块（正式回复）
     * 消息 id 由后端消息 id 派生（srv- 前缀），保证分页 prepend 时 key 稳定、滚动不跳。
     */
    private fun briefsToMessages(briefs: List<ChatApi.MessageBrief>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var reasoning = StringBuilder()
        var content = StringBuilder()
        var toolCalls = mutableListOf<String>()
        // 文件气泡：本轮助手回复里的文件信息（始终排在正文之后，与流式一致）
        var assistantFiles = mutableListOf<ChatApi.FileInfo>()
        var hasAssistant = false
        var groupId: String? = null

        fun flushAssistant() {
            if (!hasAssistant) return
            // 块顺序与流式一致：思考 -> 工具调用提示 -> 正文回复 -> 文件气泡
            val blocks = buildList {
                if (reasoning.isNotEmpty()) add(ContentBlock.Reasoning(reasoning.toString()))
                toolCalls.forEach { if (it.isNotBlank()) add(ContentBlock.ToolCall(it)) }
                if (content.isNotEmpty()) add(ContentBlock.Text(content.toString()))
                assistantFiles.forEach { info ->
                    if (info.downloadUrl.isBlank()) return@forEach
                    if (info.isImage) {
                        // 图片型 file：只展示图片预览气泡，不再展示文件卡片气泡
                        add(ContentBlock.Image(remoteUrl = info.downloadUrl, fileName = info.filename))
                    } else {
                        add(ContentBlock.File(remoteUrl = info.downloadUrl, fileName = info.filename))
                    }
                }
            }.ifEmpty { listOf(ContentBlock.Text("")) }
            result.add(ChatMessage(id = "srv-a-${groupId ?: newId()}", role = Role.ASSISTANT, blocks = blocks))
            reasoning = StringBuilder()
            content = StringBuilder()
            toolCalls = mutableListOf()
            assistantFiles = mutableListOf()
            hasAssistant = false
            groupId = null
        }

        for (b in briefs) {
            if (b.role == "assistant") {
                when (b.messageType) {
                    "reasoning" -> {
                        // 新一轮回复以 reasoning 开头：若上一条助手已有正文，先结算
                        if (content.isNotEmpty()) flushAssistant()
                        if (groupId == null) groupId = b.id
                        if (b.part.isNotEmpty()) reasoning.append(b.part)
                        hasAssistant = true
                    }
                    "tool_call" -> {   // 工具调用提示：单独成一个气泡
                        if (groupId == null) groupId = b.id
                        if (isValidToolCallPayload(b.part)) toolCalls.add(b.part.trim())
                        hasAssistant = true
                    }
                    "file" -> {   // 文件事件：单独成一个"文件气泡"，始终排在正文之后
                        if (groupId == null) groupId = b.id
                        val info = ChatApi.parseFileEvent(b.part)
                        if (info.downloadUrl.isNotBlank()) assistantFiles.add(info)
                        hasAssistant = true
                    }
                    else -> {   // token / image / 其它都并入正文
                        if (groupId == null) groupId = b.id
                        content.append(b.part)
                        hasAssistant = true
                    }
                }
            } else {
                // 用户消息：先结算上一条助手气泡，再加用户气泡
                flushAssistant()
                // 上传的附件：message_type 为 image / file 时 part 是一个访问链接，
                // 根据链接里的文件名后缀判断是图片还是普通文件，决定如何展示。
                val blocks = when (b.messageType) {
                    "image", "file" -> {
                        val info = ChatApi.parseFileEvent(b.part)
                        val name = info.filename.ifBlank { FileTypes.fileNameFromUrl(b.part) }
                        val url = info.downloadUrl.ifBlank { b.part }
                        if (b.messageType == "image" || FileTypes.isImageName(name) || info.isImage) {
                            listOf(ContentBlock.Image(remoteUrl = url, fileName = name))
                        } else {
                            listOf(ContentBlock.File(remoteUrl = url, fileName = name))
                        }
                    }
                    else -> listOf(ContentBlock.Text(b.part))
                }
                result.add(ChatMessage(id = "srv-u-${b.id}", role = Role.USER, blocks = blocks))
            }
        }
        flushAssistant()
        return result
    }

    /** 新建对话 */
    fun newConversation() {
        saveMessagesToCurrent()
        // 始终只保留一个“新对话”草稿（serverId 为空）：先清掉所有旧草稿，再置顶放新的
        conversations.removeAll { it.serverId.isBlank() }
        val conv = Conversation(id = newId(), serverId = "", title = "新对话")
        conversations.add(0, conv)       // 新草稿永远在列表最上面
        currentConversationId.value = conv.id
        conversationMessages[conv.id] = freshConversationMessages()
        pendingAttachments.clear()
        hasMoreHistory.value = false     // 新对话没有更早历史
        syncMessagesFromCurrent()
        requestScrollToBottom()
    }

    /** 删除某段历史对话（已入库的同时请求后端删除） */
    fun deleteConversation(id: String) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        val serverId = conversations[idx].serverId
        val wasCurrent = currentConversationId.value == id
        conversations.removeAt(idx)
        conversationMessages.remove(id)
        loadedServerConversations.remove(id)
        historyPage.remove(id)
        historyHasMore.remove(id)

        // 已入库的会话同步请求后端删除
        if (serverId.isNotBlank()) {
            viewModelScope.launch {
                runCatching { ChatApi.deleteConversation(serverId) }
                    .onFailure { errorMessage.value = "删除对话失败：${it.message}" }
            }
        }

        if (wasCurrent) {
            val next = conversations.firstOrNull()
            if (next != null) {
                currentConversationId.value = next.id
                hasMoreHistory.value = historyHasMore[next.id] ?: false
                syncMessagesFromCurrent()
                requestScrollToBottom()
                maybeLoadServerMessages(next.id)
            } else {
                val conv = Conversation(id = newId(), serverId = "", title = "新对话")
                conversations.add(conv)
                currentConversationId.value = conv.id
                conversationMessages[conv.id] = freshConversationMessages()
                hasMoreHistory.value = false
                syncMessagesFromCurrent()
            }
            pendingAttachments.clear()
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
    //  附件（图片 / 文件）
    // ===================================================================

    /**
     * 添加一个待发送附件（图片或普通文件）：选择后立即上传到后端 OBS，
     * 上传成功后把访问 URL 写回 [pendingAttachments]，发送时直接拼进 files。
     * 先按文件名后缀判定是否图片，用于预览区的缩略图 / 文件卡片两种展示。
     */
    fun addPendingAttachment(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = ChatApi.displayNameOf(uri, ctx)
        val isImage = FileTypes.isImageName(name)
        val item = PendingAttachment(uri = uri, fileName = name, isImage = isImage, uploading = true)
        pendingAttachments.add(item)
        viewModelScope.launch {
            try {
                val key = ChatApi.uploadFile(uri, ctx)
                val url = ChatApi.fileUrl(key)
                val idx = pendingAttachments.indexOfFirst { it.uri == uri }
                if (idx >= 0) {
                    pendingAttachments[idx] = pendingAttachments[idx].copy(fileUrl = url, uploading = false)
                }
            } catch (e: Exception) {
                val idx = pendingAttachments.indexOfFirst { it.uri == uri }
                if (idx >= 0) {
                    pendingAttachments[idx] = pendingAttachments[idx].copy(uploading = false, failed = true)
                }
            }
        }
    }

    fun removePendingAttachment(target: PendingAttachment) {
        pendingAttachments.removeAll { it.uri == target.uri }
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
     * 注意：请求体为 {message, model, conversation_id}，暂不支持图片上传，
     * 因此图片只在本地气泡里预览。
     */
    fun sendMessage(text: String) {
        if (text.isBlank() && pendingAttachments.isEmpty()) return
        if (isLoading.value) return  // 正在请求时不重复发送

        // ---- 组装用户消息：先放文字（若有），再放所有图片 ----
        // 图片在选图时已上传，这里本地预览用 localUri，发送用的访问地址用 remoteUrl。
        val blocks = mutableListOf<ContentBlock>()
        if (text.isNotBlank()) blocks.add(ContentBlock.Text(text))
        // 每个附件单独成块：图片 -> Image 块，其它文件 -> File 块；
        // UI 会把它们分别渲染成独立气泡，与文字内容分离。
        pendingAttachments.forEach {
            if (it.isImage) {
                blocks.add(ContentBlock.Image(localUri = it.uri, remoteUrl = it.fileUrl, fileName = it.fileName))
            } else {
                blocks.add(ContentBlock.File(localUri = it.uri, remoteUrl = it.fileUrl, fileName = it.fileName))
            }
        }

        val userId = newId()
        messages.add(ChatMessage(id = userId, role = Role.USER, blocks = blocks))

        // 用第一条用户文字更新对话标题
        maybeUpdateTitle(text)

        // 收集已上传成功的访问地址（选图时已上传完），作为本次发送的 files
        val files = pendingAttachments.mapNotNull { it.fileUrl }
        pendingAttachments.clear()

        startStreaming(userId, text, files)
    }

    /**
     * 重试某条失败的用户消息：重新发送该气泡的内容（文字 + 本地图片）。
     * 不重复插入用户气泡，仅清除失败标记并重新发起一次流式请求。
     */
    fun retryUserMessage(userId: String) {
        if (isLoading.value) return
        val idx = messages.indexOfFirst { it.id == userId }
        if (idx < 0) return
        val userMsg = messages[idx]
        // 取出该气泡里的文字（多块用换行拼接）
        val text = userMsg.blocks
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()
        // 取出该消息里附件（图片 + 文件）已上传的访问地址（发送时已存进 remoteUrl）
        val files = userMsg.blocks.mapNotNull { b ->
            when (b) {
                is ContentBlock.Image -> b.remoteUrl
                is ContentBlock.File -> b.remoteUrl
                else -> null
            }
        }
        if (text.isBlank() && files.isEmpty()) return
        // 清除失败标记
        messages[idx] = userMsg.copy(failed = false)
        startStreaming(userId, text, files)
    }

    /**
     * 发起一次流式请求：插入助手占位气泡，并持有请求协程。
     * @param userId   触发本次请求的用户消息 id（请求失败时用以标记可重试）
     * @param userText 发送给后端的正文
     * @param files    已上传图片的访问地址列表（选图时已完成上传），直接放进请求体 files
     */
    private fun startStreaming(userId: String, userText: String, files: List<String> = emptyList()) {
        val modelId = selectedModel.value?.id ?: "deepseek-flash"

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
        requestScrollToBottom()          // 发送后定位到最新一条底部

        // ---- 发起流式请求 ----
        val reasoning = StringBuilder()
        val content = StringBuilder()
        // 工具调用提示：每收到一个 tool_call 事件追加一条，渲染成独立气泡
        val toolCalls = mutableListOf<String>()
        // 文件事件：收到的文件信息（渲染成独立"文件气泡"，始终排在正文之后；图片额外成预览气泡）
        val fileUrls = mutableListOf<ChatApi.FileInfo>()

        // 当前会话的后端 id：新对话为空 -> 传 0，后端新建并在 meta 事件回传真实 id
        val currentServerId = conversations
            .firstOrNull { it.id == currentConversationId.value }
            ?.serverId
            .orEmpty()
        val conversationId = currentServerId.toIntOrNull() ?: 0

        sendJob = viewModelScope.launch {
            ChatApi.sendStream(userText, modelId, conversationId, files = files)
                .catch { e ->
                    // 请求失败：不再把错误写进助手气泡，
                    // 而是把触发本次请求的用户气泡标记为"可重试"（气泡左侧显示重试按钮）。
                    val uIdx = messages.indexOfFirst { it.id == userId }
                    if (uIdx >= 0) messages[uIdx] = messages[uIdx].copy(failed = true)
                    // 若助手占位气泡还没有任何实际内容，则移除它，避免留下空气泡
                    val aIdx = messages.indexOfFirst { it.id == assistantId }
                    if (aIdx >= 0) {
                        val a = messages[aIdx]
                        val hasContent = a.blocks.any { b ->
                            (b is ContentBlock.Text && b.text.isNotBlank()) ||
                            (b is ContentBlock.Reasoning && b.text.isNotBlank()) ||
                            (b is ContentBlock.ToolCall && b.text.isNotBlank()) ||
                            b is ContentBlock.Image || b is ContentBlock.File
                        }
                        if (!hasContent) messages.removeAt(aIdx)
                    }
                }
                .onCompletion {
                    isLoading.value = false
                    // 结束时把卡片级流式标记一并取消（保留已接收内容），并存档。
                    // 关键修复：之前仅整条消息 isStreaming=false，推理 / 工具调用卡片的
                    // "加载中"态是 UI 用位置启发式推断的，正文 token 一到就会被误判为结束；
                    // 现在由块自身的 isStreaming 驱动，整回合结束才会真正停止加载态。
                    updateAssistant(assistantId, reasoning.toString(), toolCalls, content.toString(), fileUrls, streaming = false)
                    saveMessagesToCurrent()
                }
                .collect { ev ->
                    when (ev) {
                        is ChatApi.StreamEvent.Meta -> {
                            // 新对话：后端此时才分配会话 id，回填到当前会话
                            onMetaReceived(ev.conversationId, ev.title)
                        }
                        is ChatApi.StreamEvent.Reasoning -> {
                            reasoning.append(ev.delta)
                            updateAssistant(assistantId, reasoning.toString(), toolCalls, content.toString(), fileUrls, streaming = true)
                        }
                        is ChatApi.StreamEvent.ToolCall -> {
                            // 工具调用提示：每个事件追加一条，作为一个独立气泡。
                            // 过滤后端噪声事件（空内容 / 字面量 "null" / 空数组 "[]"），
                            // 否则会被当成一条有效调用渲染成多余气泡（内容显示 null）。
                            val info = ev.info.trim()
                            if (isValidToolCallPayload(info)) toolCalls.add(info)
                            updateAssistant(assistantId, reasoning.toString(), toolCalls, content.toString(), fileUrls, streaming = true)
                        }
                        is ChatApi.StreamEvent.Token -> {
                            content.append(ev.delta)
                            updateAssistant(assistantId, reasoning.toString(), toolCalls, content.toString(), fileUrls, streaming = true)
                        }
                        is ChatApi.StreamEvent.File -> {
                            // 文件 / 图片事件：累积 FileInfo，去重后始终排在本轮正文（token）之后；
                            // 图片会额外渲染成图片气泡（位于文件气泡上方）。
                            val fn = ev.filename.trim()
                            val dl = ev.downloadUrl.trim()
                            val info = ChatApi.FileInfo(fn, dl, ev.isImage)
                            if (dl.isNotEmpty() && fileUrls.none { it.filename == fn && it.downloadUrl == dl }) {
                                fileUrls.add(info)
                            }
                            updateAssistant(assistantId, reasoning.toString(), toolCalls, content.toString(), fileUrls, streaming = true)
                        }
                    }
                }
        }
    }

    /**
     * 中断生成：取消正在进行的流式请求（同时会断开底层网络连接），
     * 并把当前助手气泡的"流式中"标记取消、存档。
     */
    fun stopGeneration() {
        if (!isLoading.value) return
        sendJob?.cancel()        // 取消协程 -> ChatApi 的 finally 里 disconnect 断网
        sendJob = null
        isLoading.value = false

        // 收尾：把仍在流式的助手消息标记为结束
        val idx = messages.indexOfLast { it.role == Role.ASSISTANT && it.isStreaming }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(isStreaming = false)
        }
        saveMessagesToCurrent()
    }

    /**
     * 判断 tool_call 事件的内容是否值得渲染成独立气泡。
     * 后端在"工具调用开始/结束"时常会附带噪声事件（空内容、字面量 "null"、空数组 "[]"），
     * 这类内容若直接进列表会被当成一条有效工具调用，渲染出多余的气泡（内容显示 null）。
     */
    private fun isValidToolCallPayload(info: String): Boolean {
        val t = info.trim()
        return t.isNotEmpty() && !t.equals("null", ignoreCase = true) && t != "[]"
    }

    /**
     * 更新助手消息的内容。
     * 把"思考"和"回复"拆成独立的块：
     *   - 有推理内容 -> 放一个 Reasoning 块（UI 用可折叠的"深度思考"面板展示）
     *   - 有正文内容 -> 放一个 Text 块（正式回复，展示在思考面板下方）
     * 这样就实现了"先思考、再回复"：思考阶段只有 Reasoning 块，
     * 正文开始后推理面板保持展开（"深度思考中…"），直到整回合结束才折叠为"已深度思考"。
     * 卡片级加载态由块自身的 isStreaming 驱动，而非 UI 的位置启发式，
     * 因此正文 token 到达后推理 / 工具调用卡片不会提前结束加载态。
     */
    private fun updateAssistant(
        id: String,
        reasoning: String,
        toolCalls: List<String>,
        content: String,
        fileUrls: List<ChatApi.FileInfo>,
        streaming: Boolean
    ) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return

        // 卡片级流式标记：只要整回合仍在传输（streaming=true），
        // 推理块就保持"思考中"，最后一条工具调用保持"调用中"——
        // 即使后端已经开始推送正文 token，这些卡片也不会提前结束加载态。
        // 当 streaming=false（整回合结束）时，所有块一并停止加载态。
        val lastToolIdx = toolCalls.indexOfLast { it.isNotBlank() }

        // 块顺序：思考 -> 工具调用提示（可多个）-> 正文回复
        val blocks = buildList {
            if (reasoning.isNotEmpty()) add(ContentBlock.Reasoning(reasoning, isStreaming = streaming))
            toolCalls.forEachIndexed { i, t ->
                if (t.isNotBlank()) add(ContentBlock.ToolCall(t, isStreaming = streaming && i == lastToolIdx))
            }
            if (content.isNotEmpty()) add(ContentBlock.Text(content, isStreaming = streaming))
            // 文件 / 图片气泡：始终排在正文（token）之后。
            // 图片先渲染（位于文件卡片上方），随后紧跟其文件卡片（位于下方），二者点击均可下载；
            // 非图片则只是普通文件卡片。
            fileUrls.forEach { info ->
                if (info.downloadUrl.isBlank()) return@forEach
                if (info.isImage) {
                    // 图片型 file：只展示图片预览气泡，不再展示文件卡片气泡
                    add(ContentBlock.Image(remoteUrl = info.downloadUrl, fileName = info.filename))
                } else {
                    add(ContentBlock.File(remoteUrl = info.downloadUrl, fileName = info.filename))
                }
            }
        }.ifEmpty { listOf(ContentBlock.Text("")) }

        messages[idx] = messages[idx].copy(blocks = blocks, isStreaming = streaming)
    }

    /**
     * 收到流式 meta 事件：把后端会话 id（及标题）回填到当前会话。
     * 仅当当前会话尚未入库（serverId 为空）时回填 id，避免覆盖已有会话。
     */
    private fun onMetaReceived(conversationId: String, title: String) {
        if (conversationId.isBlank()) return
        val id = currentConversationId.value ?: return
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        val conv = conversations[idx]
        if (conv.serverId.isBlank()) {
            val newTitle = if (conv.title == "新对话" && title.isNotBlank()) title else conv.title
            conversations[idx] = conv.copy(serverId = conversationId, title = newTitle)
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
            conversations[idx] = conv.copy(title = title)
        }
    }

    private fun newId() = UUID.randomUUID().toString()
}

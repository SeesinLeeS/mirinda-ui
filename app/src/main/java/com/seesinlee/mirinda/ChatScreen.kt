package com.seesinlee.mirinda

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import android.content.Intent
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.ImageRequest

/* ============================================================
 *  中断标记：出现在 agent 文本/思考结尾，需从正文剥离后单独用气泡展示。
 *  形如 "\n\n（用户中断）" 或 "\n\n（回答中断，未完成）"。
 * ============================================================ */
private val INTERRUPT_SUFFIXES = listOf(
    "\n\n（用户中断）" to "用户中断",
    "\n\n（回答中断，未完成）" to "回答中断，未完成"
)

/**
 * 从文本尾部剥离中断标记。
 * @return 净化后的文本 与 命中的气泡标签（无命中则标签为 null）。
 */
private fun stripInterruption(raw: String): Pair<String, String?> {
    for ((suffix, label) in INTERRUPT_SUFFIXES) {
        if (raw.endsWith(suffix)) {
            return raw.removeSuffix(suffix).trimEnd() to label
        }
    }
    return raw to null
}

/** 处理结果：净化后的块列表 + 收集到的中断标签（去重、保序）。 */
private data class StrippedBlocks(val blocks: List<ContentBlock>, val interruptions: List<String>)

/** 遍历消息块，剥离文本/推理块尾部的中断标记并收集中断标签。 */
private fun stripInterruptions(blocks: List<ContentBlock>): StrippedBlocks {
    val out = mutableListOf<ContentBlock>()
    val interrupts = mutableListOf<String>()
    for (block in blocks) {
        when (block) {
            is ContentBlock.Text -> {
                val (t, label) = stripInterruption(block.text)
                out.add(ContentBlock.Text(t, block.isStreaming))
                label?.let { if (it !in interrupts) interrupts.add(it) }
            }
            is ContentBlock.Reasoning -> {
                val (t, label) = stripInterruption(block.text)
                // 保留流式标记：否则 ReasoningPanel 永远拿到 isStreaming=false，
                // 会一直显示折叠态的“已深度思考”，思考内容流式追加时无法自动展开。
                out.add(ContentBlock.Reasoning(t, block.isStreaming))
                label?.let { if (it !in interrupts) interrupts.add(it) }
            }
            is ContentBlock.Image -> out.add(block)
            is ContentBlock.File -> out.add(block)
            is ContentBlock.ToolCall -> out.add(block)
        }
    }
    return StrippedBlocks(out, interrupts)
}

/* ============================================================
 *  配色（深蓝主题）—— 想换色改这里即可
 * ============================================================ */
// 主界面配色：全部引用全局 AppColors（唯一色值来源在 AppColors.kt）
object ChatColors {
    val PageBg = AppColors.PageBg
    val TopBar = AppColors.TopBar
    val InputBg = AppColors.InputBg
    val InputBgFocused = AppColors.InputBgFocused
    val InputBorder = AppColors.InputBorder
    val InputBorderFocused = AppColors.InputBorderFocused

    val UserBubble = AppColors.UserBubble
    val AssistantBubble = AppColors.AssistantBubble
    val AssistantText = AppColors.AssistantText

    val TextPrimary = AppColors.TextPrimary
    val TextSecondary = AppColors.TextSecondary
    val Accent = AppColors.Accent

    val ReasoningBg = AppColors.ReasoningBg
    val ReasoningBorder = AppColors.ReasoningBorder
    val ReasoningText = AppColors.ReasoningText

    val ToolCallBg = AppColors.ToolCallBg
    val ToolCallBorder = AppColors.ToolCallBorder
    val ToolCallText = AppColors.ToolCallText
}


/**
 * 界面层（声明式 UI）。
 * 用 ModalNavigationDrawer 包裹：左上角菜单按钮可展开抽屉，
 * 抽屉上半部分选模型、下半部分是对话历史。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // 用于点击输入框外部时收起软键盘
    val focusManager = LocalFocusManager.current

    // 软键盘弹出/收起的流畅过渡：用“目标 IME 高度”驱动 animateDpAsState，
    // 自定义时长与缓动，保证所有系统版本上输入框上移/下落都平滑有动画。
    val density = LocalDensity.current
    val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    // Scaffold 的 padding 已包含导航栏高度，这里只补“键盘超出导航栏”的部分，避免重复留白
    val extraImeBottom = with(density) { (imeTargetBottom - navBottom).coerceAtLeast(0).toDp() }
    val animatedImeBottom by animateDpAsState(
        targetValue = extraImeBottom,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "imeBottom"
    )

    // 流式输出时的"自动滚到底"：
    // 不能只监听 messages.size（流式是往同一条消息里追加内容，size 不变），
    // 这里用最后一条消息的"内容总长度"作为变化信号。
    // 注意：必须包含所有块类型（含 ToolCall / Image / File），否则在"工具调用"等
    // 非文本阶段增长时签名不变，视图不会跟随到底部。
    val lastContentSignature = vm.messages.lastOrNull()?.let { m ->
        m.blocks.sumOf { b ->
            when (b) {
                is ContentBlock.Text -> b.text.length
                is ContentBlock.Reasoning -> b.text.length
                is ContentBlock.ToolCall -> b.text.length
                // 图片/文件出现即视为一次变化，给个非零权重
                is ContentBlock.Image -> 1
                is ContentBlock.File -> 1
            }
        }
    } ?: 0

    // 最后一条消息的 id：作为“底部是否新增消息”的信号。
    // 下拉加载更早历史是往【顶部】prepend，最后一条 id 不变 -> 不会触发自动跟随，避免被拽到底部。
    val lastMessageId = vm.messages.lastOrNull()?.id

    // 用户是否已经在底部附近（若手动往上翻看历史，就不要把他强行拽回底部）。
    // 布局未就绪（可见项为空）时返回 false，避免 prepend 重排瞬间误判为“在底部”。
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= info.totalItemsCount - 2
        }
    }

    // 自动跟随到底部：仅当【底部】发生变化（追加新消息 / 流式增长）时触发；
    // key 用 lastMessageId + lastContentSignature，不再用 messages.size，避免顶部 prepend 误触发。
    LaunchedEffect(lastMessageId, lastContentSignature) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0 && isNearBottom) {
            // 大 offset 确保最后一项贴到视口底部（而非顶部对齐）
            // coerceIn 兜底：滚动过程中列表长度可能变化，避免 scrollToItem 越界崩溃
            val idx = (total - 1).coerceIn(0, total - 1)
            listState.scrollToItem(idx, 1_000_000)
        }
    }

    // 进入/切换会话、历史首页就绪、点击发送：无条件定位到最新一条底部。
    // 气泡含“深度思考”面板，其高度可能在渲染后才稳定，故延时重试几次确保真正贴底。
    LaunchedEffect(vm.scrollToBottomSignal.value) {
        if (vm.messages.isNotEmpty()) {
            repeat(4) {
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0) {
                    // 大 offset 把最后一项推到视口底部（而非顶部对齐），LazyColumn 会自动 clamp
                    listState.scrollToItem(total - 1, 1_000_000)
                }
                delay(40)
            }
        }
    }

    // 下拉到顶部附近：按页加载更早的历史消息（更早的拼到列表顶部，靠 key 锚定不跳动）。
    // index 0 即首条消息，滑到顶时锚定在消息上，prepend 后 index 变大、isNearTop 变 false，
    // 不会形成“加载->锚点不动->再次加载”的抖动循环。
    val isNearTop by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 && listState.firstVisibleItemIndex <= 0
        }
    }
    LaunchedEffect(isNearTop, vm.hasMoreHistory.value, vm.isLoadingMore.value) {
        if (isNearTop && vm.hasMoreHistory.value && !vm.isLoadingMore.value) {
            vm.loadOlderMessages()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                vm = vm,
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            focusManager.clearFocus()   // 点菜单栏也收起软键盘
                            scope.launch { drawerState.open() }
                            // 打开菜单即刷新：可用模型列表 + 历史会话列表
                            vm.loadModels()
                            vm.refreshConversationsFromServer()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "菜单",
                                tint = ChatColors.TextPrimary
                            )
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Mirinda",
                                style = MaterialTheme.typography.titleLarge,
                                color = ChatColors.TextPrimary
                            )
                            vm.selectedModel.value?.let {
                                Text(
                                    it.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChatColors.TextSecondary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = ChatColors.TopBar
                    )
                )
            },
            containerColor = ChatColors.PageBg   // 整个页面深蓝背景
        ) { padding ->
            // 只有在"助手占位消息还一个字都没吐出来"时，才单独显示底部"思考中"动效；
            // 一旦开始流式输出（推理或正文），就由气泡内的"深度思考"面板接管提示。
            val lastMsg = vm.messages.lastOrNull()
            val waitingFirstChunk = vm.isLoading.value &&
                lastMsg?.role == Role.ASSISTANT &&
                lastMsg.blocks.all { b ->
                    when (b) {
                        is ContentBlock.Text -> b.text.isBlank()
                        is ContentBlock.Reasoning -> b.text.isBlank()
                        else -> false
                    }
                }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 软键盘弹出时，用带缓动的动画 padding 把底部输入框平滑顶到键盘上方
                    .padding(bottom = animatedImeBottom)
                    // 点击输入框以外的任意区域（消息区/空白）都收起软键盘；
                    // 输入框内部的点击会被其自身消费，不会触发这里。
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
            ) {
                // 模型加载/网络失败：红色横幅横在消息列表上方，占据自身布局高度，
                // 不与消息气泡、"已深度思考"面板叠放，避免被遮挡。
                vm.errorMessage.value?.let { err ->
                    Surface(
                        color = Color(0xFF5A1A1A),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            color = Color(0xFFFFD6D6),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }

                // 用 Box 包住列表：加载更早的菊花作为【覆盖层】显示在顶部，
                // 不占用列表项 -> 不改变项数与布局 -> prepend 时不抖动。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        // 顶/底间距用 contentPadding，避免在列表顶部放占位 item
                        // （占位 item 会霸占 index 0，破坏 prepend 的 key 锚定、导致抖动）
                        contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // index 0 直接是第一条真实消息：滑到顶时锚定在消息上，
                        // prepend 后该消息被下推、firstVisibleItemIndex 变大，
                        // isNearTop 自然变 false，不会反复触发加载。
                        items(vm.messages, key = { it.id }) { msg ->
                            MessageBubble(msg, onRetry = { vm.retryUserMessage(it) })
                        }
                        if (waitingFirstChunk) {
                            item { TypingIndicator() }
                        }
                    }

                    // 顶部加载指示（覆盖层）
                    if (vm.isLoadingMore.value) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = ChatColors.TextSecondary
                            )
                        }
                    }
                }

                InputBar(
                    pendingAttachments = vm.pendingAttachments,
                    isLoading = vm.isLoading.value,
                    onPickAttachment = { vm.addPendingAttachment(it) },
                    onRemoveAttachment = { vm.removePendingAttachment(it) },
                    onSend = { vm.sendMessage(it) },
                    onStop = { vm.stopGeneration() }
                )
            }
        }
    }
}

/**
 * 抽屉内容：上半部分可选模型，下半部分对话历史。
 */
@Composable
fun AppDrawer(
    vm: ChatViewModel,
    onCloseDrawer: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = ChatColors.TopBar,
        modifier = Modifier.fillMaxWidth(0.82f)
    ) {
        var showSettings by remember { mutableStateOf(false) }

        if (showSettings) {
            SettingsDialog(vm = vm, onDismiss = { showSettings = false })
        }

        // 对话历史列表的滚动状态：用于“滑到底部附近自动加载下一页”
        val convListState = rememberLazyListState()
        val isNearConvBottom by remember {
            derivedStateOf {
                val info = convListState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                // 已渲染到倒数第 3 项之内就算接近底部（列表为空时不触发）
                info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
            }
        }
        LaunchedEffect(isNearConvBottom, vm.hasMoreConversations.value, vm.isLoadingMoreConversations.value) {
            if (isNearConvBottom && vm.hasMoreConversations.value && !vm.isLoadingMoreConversations.value) {
                vm.loadMoreConversations()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            // ============ 上半部分：选择模型 ============
            Text(
                "选择模型",
                style = MaterialTheme.typography.titleMedium,
                color = ChatColors.TextPrimary
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (vm.availableModels.isEmpty()) {
                    Text(
                        "暂无可用模型（等待后端返回）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ChatColors.TextSecondary
                    )
                }
                vm.availableModels.forEach { model ->
                    val selected = vm.selectedModel.value?.id == model.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) ChatColors.Accent.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(
                            1.dp,
                            if (selected) ChatColors.InputBorderFocused else ChatColors.InputBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.selectModel(model) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { vm.selectModel(model) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = ChatColors.InputBorderFocused,
                                    unselectedColor = ChatColors.TextSecondary
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    model.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ChatColors.TextPrimary
                                )
                                if (model.desc.isNotBlank()) {
                                    Text(
                                        model.desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ChatColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = ChatColors.InputBorder)
            Spacer(Modifier.height(12.dp))

            // ============ 下半部分：对话历史 ============
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "对话历史",
                    style = MaterialTheme.typography.titleMedium,
                    color = ChatColors.TextPrimary
                )
                Spacer(Modifier.weight(1f))
                // 新建对话
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, ChatColors.InputBorder),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable {
                            vm.newConversation()
                            onCloseDrawer()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "新建对话",
                            tint = ChatColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                state = convListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 只展示后端返回的历史会话（serverId 非空），不展示本地“新对话”草稿
                items(
                    vm.conversations.filter { it.serverId.isNotBlank() },
                    key = { it.id }
                ) { conv ->
                    val current = vm.currentConversationId.value == conv.id
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (current) ChatColors.Accent.copy(alpha = 0.18f)
                        else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.switchConversation(conv.id)
                                onCloseDrawer()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 标题 + 时间
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conv.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (current) ChatColors.TextPrimary else ChatColors.TextSecondary,
                                    maxLines = 1
                                )
                                if (conv.createdAt.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        conv.createdAt,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ChatColors.TextSecondary.copy(alpha = 0.7f),
                                        maxLines = 1
                                    )
                                }
                            }
                            // 删除按钮
                            IconButton(
                                onClick = { vm.deleteConversation(conv.id) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除对话",
                                    tint = ChatColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // 列表底部：加载下一页时显示的菊花
                if (vm.isLoadingMoreConversations.value) {
                    item(key = "__conv_loading_more__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = ChatColors.TextSecondary
                            )
                        }
                    }
                }
            }

            // ============ 底部：左下角设置（齿轮）============
            HorizontalDivider(color = ChatColors.InputBorder)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = ChatColors.TextPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showSettings = true }
                        .padding(4.dp)
                        .size(22.dp)
                )
                Spacer(Modifier.weight(1f))
                // 登录状态小圆点 + 文字
                val (dotColor, label) = when (vm.loginState.value) {
                    ChatViewModel.LoginState.SUCCESS -> Color(0xFF4CAF50) to "已登录"
                    ChatViewModel.LoginState.LOADING -> ChatColors.TextSecondary to "登录中…"
                    ChatViewModel.LoginState.FAILED  -> Color(0xFFE57373) to "未登录"
                    ChatViewModel.LoginState.IDLE    -> ChatColors.TextSecondary to "—"
                }
                Surface(shape = CircleShape, color = dotColor, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ChatColors.TextSecondary
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 设置页面（弹窗）：查看/编辑登录 token，查看登录状态，重新生成默认 token。
 */
@Composable
fun SettingsDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    // 本地编辑态，初始填入当前 token
    var input by remember { mutableStateOf(TextFieldValue(vm.token.value)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ChatColors.TopBar,
        title = {
            Text("设置", color = ChatColors.TextPrimary)
        },
        text = {
            Column {
                Text(
                    "登录 Token",
                    style = MaterialTheme.typography.labelLarge,
                    color = ChatColors.TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = false,
                    maxLines = 3,
                    placeholder = { Text("留空保存将自动生成一个默认 token") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ChatColors.TextPrimary,
                        unfocusedTextColor = ChatColors.TextPrimary,
                        focusedBorderColor = ChatColors.InputBorderFocused,
                        unfocusedBorderColor = ChatColors.InputBorder,
                        cursorColor = ChatColors.InputBorderFocused
                    )
                )
                Spacer(Modifier.height(10.dp))

                // 登录状态
                val statusText = when (vm.loginState.value) {
                    ChatViewModel.LoginState.SUCCESS -> "✅ 已登录"
                    ChatViewModel.LoginState.LOADING -> "⏳ 登录中…"
                    ChatViewModel.LoginState.FAILED  -> "❌ ${vm.loginMessage.value.ifBlank { "未登录" }}"
                    ChatViewModel.LoginState.IDLE    -> "尚未登录"
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.TextSecondary
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "留空保存将使用设备默认 Token。",
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatColors.TextSecondary.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveToken(input.text)
                onDismiss()
            }) {
                Text("保存并登录", color = ChatColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = ChatColors.TextSecondary)
            }
        }
    )
}

/** 单条消息气泡 */
@Composable
fun MessageBubble(msg: ChatMessage, onRetry: (String) -> Unit = {}) {
    val isUser = msg.role == Role.USER

    if (isUser) {
        // 用户消息：文字 / 每张图片 / 每个文件各自成一个独立气泡，靠右展示（文字在上、附件在下）。
        // 图片、文件不再塞进文字气泡，而是与文字内容分离、各占一行单独展示。
        //
        // 发送失败时：只展示文字内容气泡，隐藏图片/文件气泡，
        // 这样"重试"按钮只需挂在文字气泡上即可；底层 blocks 仍保留附件，
        // 点击重试会连同文件一起重新发送。
        val visibleBlocks = if (msg.failed) {
            msg.blocks.filter { it is ContentBlock.Text && it.text.isNotBlank() }
        } else {
            msg.blocks
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (visibleBlocks.isEmpty() && msg.failed) {
                // 兜底：失败消息没有文字内容（例如仅发送了文件）时，
                // 仍单独显示一个重试按钮，避免用户无法重发。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RetryButton(onClick = { onRetry(msg.id) })
                }
            } else {
                visibleBlocks.forEachIndexed { index, block ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 请求失败：在（唯一的）文字气泡左侧显示"重试"按钮，点击后重新发送该消息
                        if (msg.failed && index == 0) {
                            RetryButton(onClick = { onRetry(msg.id) })
                            Spacer(Modifier.width(8.dp))
                        }
                        UserBlockBubble(block)
                    }
                }
            }
        }
    } else {
        // 助手消息：不包气泡，直接平铺（左对齐，近白文字适配深蓝背景）
        // 其中"推理块"单独用可折叠的深度思考面板展示，实现"先思考、再回复"。
        // 结尾若带 "\n\n（xxx中断）" 标记，则剥离该标记（不进正文），并单独用气泡展示括号内容。
        val stripped = stripInterruptions(msg.blocks)
        val reasoningBlock = stripped.blocks.filterIsInstance<ContentBlock.Reasoning>().firstOrNull()
        val otherBlocks = stripped.blocks.filterNot { it is ContentBlock.Reasoning }

        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
            ) {
            if (reasoningBlock != null && reasoningBlock.text.isNotBlank()) {
                // thinking 跟随该推理块自身的流式状态：只要本回合还在传输（即便正文 token 已来），
                // 推理卡片就保持"深度思考中…"，不再因正文出现而提前结束加载态。
                ReasoningPanel(
                    text = reasoningBlock.text,
                    thinking = reasoningBlock.isStreaming
                )
                Spacer(Modifier.height(8.dp))
            }
            // 工具调用卡片的"调用中"状态同样跟随块自身的流式状态：
            // 最后一条工具调用在整回合结束前都保持加载态，不会被后续 token 提前终止。
            val lastOtherIndex = otherBlocks.lastIndex
            otherBlocks.forEachIndexed { index, block ->
                val toolActive = block is ContentBlock.ToolCall && block.isStreaming
                MessageBlock(block, ChatColors.TextPrimary, toolActive)
                // 块间距与"深度思考"面板一致（8.dp），避免工具气泡紧贴正文内容
                if (index != lastOtherIndex) Spacer(Modifier.height(8.dp))
            }

            // 中断标记气泡：左对齐小气泡，风格与"深度思考"面板一致
            stripped.interruptions.forEach { label ->
                Spacer(Modifier.height(8.dp))
                InterruptionBubble(label)
            }
            }
        }
    }
}

/** 消息内的单个内容块（文字 / 图片 / 文件），气泡内外通用 */
@Composable
private fun MessageBlock(
    block: ContentBlock,
    textColor: Color = Color.Unspecified,
    toolActive: Boolean = false
) {
    when (block) {
        is ContentBlock.Text -> {
            // 用轻量 Markdown 渲染器解析（粗体/代码块/列表/标题/链接等）
            MarkdownText(
                markdown = block.text,
                color = textColor
            )
        }
        is ContentBlock.Reasoning -> {
            // 正常情况下推理块已在 MessageBubble 里单独处理；这里兜底渲染。
            ReasoningPanel(text = block.text, thinking = false)
        }
        is ContentBlock.Image -> ImageBubble(block)
        is ContentBlock.File -> FileBubble(block)
        is ContentBlock.ToolCall -> ToolCallBubble(block.text, active = toolActive)
    }
}

/** 请求失败时显示在气泡旁的"重试"小圆钮。 */
@Composable
private fun RetryButton(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ChatColors.InputBorder),
        modifier = Modifier
            .size(30.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "重试",
                tint = ChatColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 用户消息里的单个内容块，各自渲染成一个独立气泡：
 *  - 文字 -> 圆角文字气泡；
 *  - 图片 -> 独立图片气泡（与文字分离）；
 *  - 文件 -> 文件卡片气泡（图标 + 文件名 + 类型）。
 */
@Composable
private fun UserBlockBubble(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text -> {
            if (block.text.isBlank()) return
            Surface(
                color = ChatColors.UserBubble,
                contentColor = ChatColors.TextPrimary,
                shape = RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp, bottomEnd = 0.dp, bottomStart = 20.dp
                ),
                shadowElevation = 1.dp,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                SelectionContainer {
                    MarkdownText(
                        markdown = block.text,
                        color = ChatColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                }
            }
        }
        is ContentBlock.Image -> ImageBubble(block)
        is ContentBlock.File -> FileBubble(block)
        is ContentBlock.Reasoning -> { /* 用户消息不含推理块，忽略 */ }
        is ContentBlock.ToolCall -> { /* 用户消息不含工具调用块，忽略 */ }
    }
}

/**
 * 图片气泡：单独成块展示一张图片（本地或远程）。
 * 点击行为：
 *  - 远程图片（remoteUrl）：弹"是否下载"确认框，确认后走系统下载管理器落盘到「下载」目录；
 *  - 本地图片（发送前预览，仅 localUri）：直接以 ACTION_VIEW 打开。
 * 逻辑与 [FileBubble] 一致，仅展示形态不同（图片预览 vs 文件卡片）。
 */
@Composable
private fun ImageBubble(block: ContentBlock.Image) {
    val context = LocalContext.current
    val remoteUrl: String? = block.remoteUrl?.takeIf { it.isNotBlank() }
    val localUri = block.localUri
    val name = block.fileName.ifBlank { FileTypes.fileNameFromUrl(block.remoteUrl) }
    // 点击后是否弹出"是否下载"确认框
    var showDownloadDialog by remember { mutableStateOf(false) }
    // 地址非法时弹出的"确定"提示框
    var showInvalidUrlDialog by remember { mutableStateOf(false) }

    val model: Any? = remoteUrl ?: localUri
    if (model != null) {
        // 关键：限制解码尺寸（最长边 720px），避免历史里大图在全设备（尤其低配真机/模拟器）
        // 上因原图过大而 OOM 崩溃；远程/本地图统一走 Coil 的降采样与磁盘缓存。
        val imageRequest = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .size(720)
            .crossfade(true)
            .apply {
                // 远程图片来自需要鉴权的后端 /file 接口：必须带上 x-user-token，
                // 否则服务端返回 401，Coil 加载失败，气泡只剩空白占位。
                if (remoteUrl != null) {
                    val token = TokenStore.getToken()
                    if (token.isNotBlank()) addHeader(ChatApi.HEADER_TOKEN, token)
                }
            }
            .build()
        // DisableSelection：助手消息整体包在 SelectionContainer 里（用于文字选择），
        // 会拦截子元素的点按手势；这里局部关闭选择，确保图片气泡的点击能正常触发。
        DisableSelection {
        AsyncImage(
            model = imageRequest,
            contentDescription = block.fileName.ifBlank { null },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (remoteUrl != null || localUri != null) Modifier.clickable {
                        if (remoteUrl != null) {
                            // 远程图片：先弹出确认框，确认后再真正下载
                            showDownloadDialog = true
                        } else if (localUri != null) {
                            // 本地图片（发送前预览）：直接以 ACTION_VIEW 打开
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, localUri)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    } else Modifier
                )
        )
        }
    }

    // 点击远程图片气泡后弹出的"是否下载"确认框；确认才真正调用 DownloadManager 下载
    if (showDownloadDialog && remoteUrl != null) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            containerColor = ChatColors.TopBar,
            title = {
                Text("下载图片", color = ChatColors.TextPrimary)
            },
            text = {
                Text(
                    "是否下载图片「$name」？",
                    color = ChatColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    // 地址非法时不下载，改为弹"确定"提示框，避免 DownloadManager 抛异常崩溃
                    if (isValidHttpUrl(remoteUrl)) {
                        downloadFile(context, remoteUrl, name)
                    } else {
                        showInvalidUrlDialog = true
                    }
                }) {
                    Text("下载", color = ChatColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("取消", color = ChatColors.TextSecondary)
                }
            }
        )
    }

    // 地址非法时的提示框：只有一个"确定"按钮，点击即关闭。
    if (showInvalidUrlDialog) {
        AlertDialog(
            onDismissRequest = { showInvalidUrlDialog = false },
            containerColor = ChatColors.TopBar,
            title = { Text("无法下载", color = ChatColors.TextPrimary) },
            text = { Text("图片地址无效，无法下载。", color = ChatColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showInvalidUrlDialog = false }) {
                    Text("确定", color = ChatColors.Accent)
                }
            }
        )
    }
}

/**
 * 用系统下载管理器把远程文件真正下载到设备的「下载」目录（而非在浏览器里在线预览）。
 *
 * 下载完成后会在通知栏显示"下载完成"，点击通知即可用对应应用打开。
 * 若下载管理器不可用（理论不会），则回退为浏览器打开该 URL。
 *
 * 注意：通过 [DownloadManager] 写入公共「下载」目录不需要应用自行申请存储权限，
 * 因为下载由系统进程代为执行。
 *
 * 鉴权：下载受保护文件时，必须带上用户 token（x-user-token 头），否则后端会拒绝。
 * 这里与 [ChatApi] 的其它接口保持一致，统一从 [TokenStore] 取当前 token。
 *
 * @param url      文件可访问地址（download_url）
 * @param filename 文件名（用于落盘文件名与通知标题）
 */
/**
 * 判断字符串是否为可用的 http(s) 下载地址。
 * 用于拦截非法 URL，避免直接交给 DownloadManager 构造 Request 时抛
 * IllegalArgumentException 导致应用崩溃。
 */
private fun isValidHttpUrl(url: String): Boolean {
    val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val s = u.scheme?.lowercase() ?: return false
    return s == "http" || s == "https"
}

private fun downloadFile(context: Context, url: String, filename: String) {
    // 防御：非法 / 非 http(s) 地址直接放弃，绝不交给 DownloadManager（其构造会抛异常）。
    if (!isValidHttpUrl(url)) return
    val dm = context.getSystemService(DownloadManager::class.java)
    if (dm == null) {
        // 兜底：下载管理器取不到，退回到浏览器打开（可能仍是预览）
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return
    }

    val name = filename.ifBlank { FileTypes.fileNameFromUrl(url).ifBlank { "download" } }
    // 构造 Request 同样可能抛异常，整体包进 runCatching 兜底，避免任何意外地址导致崩溃。
    val request = runCatching {
        DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("正在下载文件…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            // 带上用户 token，让后端鉴权通过（与 ChatApi 的其它接口同一套头名）
            .addRequestHeader(ChatApi.HEADER_TOKEN, TokenStore.getToken())
            .also { req ->
                // 尽量带上 MIME 类型，便于下载完成后系统挑选合适的打开方式
                FileTypes.extensionOf(name).let { ext ->
                    if (ext.isNotBlank()) {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { req.setMimeType(it) }
                    }
                }
            }
    }.getOrNull() ?: return

    runCatching { dm.enqueue(request) }
}

/**
 * 文件卡片气泡：展示非图片文件（xlsx / docx / pdf 等）。
 * 左侧按文件类型显示一个图标，右侧显示文件名与类型标签。
 */
@Composable
private fun FileBubble(block: ContentBlock.File) {
    val context = LocalContext.current
    val name = block.fileName.ifBlank { "文件" }
    val ext = FileTypes.extensionOf(name)
    val icon = when (ext) {
        "xls", "xlsx", "csv" -> Icons.Filled.TableChart
        "doc", "docx", "txt", "md", "rtf" -> Icons.Filled.Description
        "pdf" -> Icons.Filled.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Filled.FolderZip
        "mp3", "wav", "flac", "aac", "m4a" -> Icons.Filled.AudioFile
        "mp4", "mov", "avi", "mkv", "webm" -> Icons.Filled.VideoFile
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    // 点击气泡的行为：
    //  - 远程文件（remoteUrl）：走系统下载管理器（DownloadManager），把文件真正下载到
    //    「下载」目录，并在通知栏提示"下载完成"，点击通知即可用对应应用打开。
    //    这避免了直接 ACTION_VIEW 打开 http(s) URL 时浏览器"在线预览"、不落盘的问题。
    //  - 本地文件（发送前预览，仅 localUri）：直接以 ACTION_VIEW 打开。
    val remoteUrl: String? = block.remoteUrl?.takeIf { it.isNotBlank() }
    // 点击后是否弹出"是否下载"确认框
    var showDownloadDialog by remember { mutableStateOf(false) }
    // 地址非法时弹出的"确定"提示框
    var showInvalidUrlDialog by remember { mutableStateOf(false) }
    // DisableSelection：助手消息整体包在 SelectionContainer 里（用于文字选择），
    // 会拦截子元素的点按手势；这里局部关闭选择，确保文件气泡的点击能正常触发。
    DisableSelection {
    Surface(
        color = ChatColors.UserBubble,
        contentColor = ChatColors.TextPrimary,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
        // 固定宽高，所有文件气泡尺寸一致
        modifier = Modifier
            .width(240.dp)
            .height(64.dp)
            .then(
                if (remoteUrl != null || block.localUri != null) Modifier.clickable {
                    if (remoteUrl != null) {
                        // 远程文件：先弹出确认框，确认后再真正下载
                        showDownloadDialog = true
                    } else {
                        block.localUri?.let { uri ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ChatColors.Accent.copy(alpha = 0.9f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = name,
                    color = ChatColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = FileTypes.labelOf(name),
                    color = ChatColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
    }

    // 点击远程文件气泡后弹出的"是否下载"确认框；确认才真正调用 DownloadManager 下载
    if (showDownloadDialog && remoteUrl != null) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            containerColor = ChatColors.TopBar,
            title = {
                Text("下载文件", color = ChatColors.TextPrimary)
            },
            text = {
                Text(
                    "是否下载文件「$name」？",
                    color = ChatColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    // 地址非法时不下载，改为弹"确定"提示框，避免 DownloadManager 抛异常崩溃
                    if (isValidHttpUrl(remoteUrl)) {
                        downloadFile(context, remoteUrl, name)
                    } else {
                        showInvalidUrlDialog = true
                    }
                }) {
                    Text("下载", color = ChatColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("取消", color = ChatColors.TextSecondary)
                }
            }
        )
    }

    // 地址非法时的提示框：只有一个"确定"按钮，点击即关闭。
    if (showInvalidUrlDialog) {
        AlertDialog(
            onDismissRequest = { showInvalidUrlDialog = false },
            containerColor = ChatColors.TopBar,
            title = { Text("无法下载", color = ChatColors.TextPrimary) },
            text = { Text("文件地址无效，无法下载。", color = ChatColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showInvalidUrlDialog = false }) {
                    Text("确定", color = ChatColors.Accent)
                }
            }
        )
    }
}

/**
 * 中断标记气泡：当 agent 文本/思考结尾带有 "\n\n（xxx中断）" 时，
 * 抽取括号内容，用与整体 UI 一致的小气泡单独展示（左对齐，呼应助手消息）。
 */
/**
 * 工具调用提示气泡：交互与"深度思考"面板一致，可折叠。
 *
 * - active=true（工具正在调用、后面还没有新内容）：默认展开，标题显示"工具调用中…"并带转圈动效。
 * - active=false（调用结束 / 历史消息）：自动折叠成一行"🔧 工具调用"，点一下可展开回看详情。
 *
 * text 为后端 tool_call 事件的提示内容。优化后优先按 `ToolInfo` 列表（[{"name","type"}]）解析，
 * 折叠态标题直接带上工具名，展开态逐条展示工具名 + 类型标签；解析失败时回退原样展示文本。
 */

/** 后端 tool_call 消息体里的单个工具信息 */
private data class ToolInfo(val name: String, val type: String)

/**
 * 把 tool_call 的提示内容解析成 [ToolInfo] 列表。
 * 兼容两种形态：JSON 数组 `[{...}]`（一次推送多个工具）或单个 JSON 对象 `{...}`（一次一个工具）。
 * 无法解析（非 JSON / 字段缺失）时返回 null，调用方回退原样展示。
 */
private fun parseToolInfos(raw: String): List<ToolInfo>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        val list = mutableListOf<ToolInfo>()
        when {
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { o ->
                        list.add(ToolInfo(o.optString("name"), o.optString("type")))
                    }
                }
            }
            trimmed.startsWith("{") -> {
                val o = JSONObject(trimmed)
                list.add(ToolInfo(o.optString("name"), o.optString("type")))
            }
            else -> return null
        }
        list.ifEmpty { null }
    }.getOrNull()
}

@Composable
private fun ToolCallBubble(text: String, active: Boolean) {
    val tools = parseToolInfos(text)
    val detail = text.trim().ifEmpty { "正在调用工具…" }
    // 展开状态：调用中默认展开；结束/历史默认折叠（仍可手动点开）。
    var expanded by remember { mutableStateOf(active) }
    LaunchedEffect(active) { expanded = active }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ChatColors.ToolCallBg,
            border = BorderStroke(1.dp, ChatColors.ToolCallBorder),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // ---- 标题行（可点击折叠/展开）----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (active) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = ChatColors.Accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "工具调用中…",
                            color = ChatColors.ToolCallText,
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else {
                        // 折叠态：只显示"工具调用"，不暴露工具名称（名称在展开后查看）
                        Text("\uD83D\uDD27", style = MaterialTheme.typography.labelLarge)  // 🔧
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "工具调用",
                            color = ChatColors.ToolCallText,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = ChatColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // ---- 工具调用详情（可折叠）----
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        if (tools != null) {
                            // 已按 ToolInfo 列表解析：逐条展示「工具名 + 类型标签」
                            tools.forEachIndexed { i, tool ->
                                ToolInfoRow(tool)
                                if (i != tools.lastIndex) Spacer(Modifier.height(6.dp))
                            }
                        } else {
                            // 回退：原样展示后端文本
                            Text(
                                text = detail,
                                color = ChatColors.ToolCallText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 判断工具是否应渲染为可点击链接：
 *  - type 属于链接类（url/link/web…），或
 *  - name 本身就是一个网址（http(s):// 开头，或形如 example.com 的裸域名）。
 * 内容（工具名/网址）始终放在 name 中，故链接地址取 name。
 */
private fun isLinkTool(tool: ToolInfo): Boolean {
    if (tool.type.trim().lowercase() in setOf("url", "link", "href", "web", "website")) return true
    val n = tool.name.trim()
    if (n.startsWith("http://", ignoreCase = true) || n.startsWith("https://", ignoreCase = true)) return true
    // 裸域名（如 example.com）也按链接处理
    return n.contains(".") && !n.contains(" ") && android.util.Patterns.WEB_URL.matcher(n).matches()
}

/** 用系统浏览器打开链接；name 视为地址，缺失协议时补 https:// */
private fun openToolLink(context: android.content.Context, raw: String) {
    val url = raw.trim()
    if (url.isBlank()) return
    val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixed))) }
}

/**
 * 工具信息单行：
 *  - 普通类型：工具名（加粗）+ 类型标签（圆角小药丸）
 *  - 链接类型（url/link/web…）：工具名渲染成可点击链接（主题色 + 下划线），点按用浏览器打开
 */
@Composable
private fun ToolInfoRow(tool: ToolInfo) {
    val context = LocalContext.current
    val linkType = isLinkTool(tool)
    val label = tool.name.ifBlank { "未命名工具" }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (linkType) Modifier.clickable { openToolLink(context, label) } else Modifier
    ) {
        Text(
            text = label,
            color = if (linkType) ChatColors.Accent else ChatColors.ToolCallText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textDecoration = if (linkType) TextDecoration.Underline else TextDecoration.None,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (tool.type.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = ChatColors.ToolCallBorder.copy(alpha = 0.22f)
            ) {
                Text(
                    text = tool.type,
                    color = ChatColors.ToolCallText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun InterruptionBubble(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ChatColors.ReasoningBg,
            border = BorderStroke(1.dp, ChatColors.ReasoningBorder),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = label,
                color = ChatColors.ReasoningText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** "正在输入"提示 */
@Composable
fun TypingIndicator() {
    // 与助手消息一致：不包气泡，直接平铺左对齐
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = ChatColors.Accent
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "正在思考…",
            color = ChatColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


/**
 * 思考中气泡的【固定高度】：流式输出期间内容在内部滚动，不随文本长度无限增高。
 * 改这个值即可调整思考中气泡的视觉高度。
 */
private val REASONING_THINKING_HEIGHT = 120.dp

/**
 * 深度思考面板（DeepSeek-R1 风格）。
 *
 * - thinking=true（正在监听 agent 的 SSE 流式消息、仍在思考）：
 *   气泡【固定高度】，思考内容在内部追加并通过 auto-scroll 形成"打字滚动"效果；
 *   此阶段【不可点击展开】，只显示"深度思考中…"标题 + 转圈。
 * - thinking=false（思考 / 整回合结束 / 历史消息）：
 *   气泡默认【折叠】为一行"已深度思考（点击展开）"，点击后正常展示完整内容，
 *   气泡高度随文本长度自适应（wrap 内容）。
 */
@Composable
fun ReasoningPanel(text: String, thinking: Boolean) {
    // 思考结束后的折叠/展开态：思考结束或历史消息都默认折叠，点击再展开。
    // 初值取 false，避免进入历史会话时按展开态算好滚动位置、随即折叠导致停在面板处。
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(thinking) {
        // 一旦思考结束（thinking=false）强制回到折叠态；思考中无需此态。
        if (!thinking) expanded = false
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ChatColors.ReasoningBg,
        border = BorderStroke(1.dp, ChatColors.ReasoningBorder),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // 仅思考结束（非流式）后才允许点击折叠/展开；思考中禁止点击。
                if (!thinking) Modifier.clickable { expanded = !expanded }
                else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // ---- 标题行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (thinking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ChatColors.Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "深度思考中…",
                        color = ChatColors.ReasoningText,
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Text("💡", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已深度思考",
                        color = ChatColors.ReasoningText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.weight(1f))
                // 思考中不显示展开/折叠箭头（无内容可展开）；结束后才显示。
                if (!thinking) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = ChatColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ---- 内容区 ----
            if (thinking) {
                // 固定高度容器：思考内容内部追加、自动滚到底，形成"打字滚动"效果。
                val scrollState = rememberScrollState()
                // 文本增长时跟随到底部；同时监听 maxValue，规避"文本已更新但布局尚未
                // 重算导致 maxValue 滞后"的滚动不到位问题（两次 emit 兜底）。
                LaunchedEffect(Unit) {
                    snapshotFlow { text to scrollState.maxValue }.collect {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(REASONING_THINKING_HEIGHT)
                        .padding(top = 8.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = text,
                        color = ChatColors.ReasoningText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // 思考结束：按折叠态展示，点击后 wrap 内容自适应高度。
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = text,
                            color = ChatColors.ReasoningText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}


/**
 * 底部输入框（深蓝页面 / 浅蓝输入框 / 聚焦高亮 hover 效果）
 * 图片预览与 "+" 添加格在同一流式行：加 N 张图后，"+" 自动排到第 N+1 格，图多会自动换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputBar(
    pendingAttachments: List<PendingAttachment>,
    isLoading: Boolean,
    onPickAttachment: (Uri) -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }   // 是否聚焦 -> 控制 hover 效果
    // 让“点击整个输入框区域”都能聚焦到文本框并弹出软键盘
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current   // 发送后回收软键盘用

    val maxAttachments = 9               // 最多可添加的附件数
    val tileSize = 54.dp                 // 缩略图 / 文件卡片 格子的尺寸

    // 使用通用文件选择器：不仅图片，xlsx / docx / pdf 等任意文件都可选择上传
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onPickAttachment) }

    // 有附件正在上传时禁用发送，避免把尚未拿到访问地址的附件发出去
    val uploading = pendingAttachments.any { it.uploading }
    val canSend = !isLoading && !uploading && (text.text.isNotBlank() || pendingAttachments.isNotEmpty())

    fun doSend() {
        if (canSend) {
            onSend(text.text)
            text = TextFieldValue("")
            focusManager.clearFocus()   // 发送后回收软键盘、输入框回落
        }
    }

    fun launchPicker() {
        // "*/*" 表示允许选择任意类型文件（图片 / 表格 / 文档 等）
        picker.launch("*/*")
    }

    // ---- 聚焦时的动画过渡：背景变亮、边框变亮变粗（hover 效果）----
    val containerColor by animateColorAsState(
        targetValue = if (isFocused) ChatColors.InputBgFocused else ChatColors.InputBg,
        label = "bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) ChatColors.InputBorderFocused else ChatColors.InputBorder,
        label = "border"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.dp,
        label = "borderW"
    )

    Surface(color = ChatColors.PageBg) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = containerColor,
                border = BorderStroke(borderWidth, borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    // 点击整个输入框（含内边距/空白）都聚焦文本框并弹出键盘；无点击涟漪
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { focusRequester.requestFocus() }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

                    // ---- 1) 附件预览（图片缩略图 / 文件卡片）+ "+" 添加格（流式换行）----
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 已选附件：图片显示缩略图，其它文件显示文件卡片
                        pendingAttachments.forEach { att ->
                            if (att.isImage) {
                                Box(modifier = Modifier.size(tileSize)) {
                                    AsyncImage(
                                        model = att.uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                    // 上传中 / 失败 时的半透明遮罩提示
                                    if (att.uploading || att.failed) {
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (att.uploading) "上传中" else "失败",
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(20.dp)
                                            .clickable { onRemoveAttachment(att) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "删除",
                                            tint = Color.White,
                                            modifier = Modifier.padding(3.dp)
                                        )
                                    }
                                }
                            } else {
                                // 文件卡片：图标 + 文件名 + 上传状态；右上角可删除
                                Box {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.06f),
                                        border = BorderStroke(1.dp, ChatColors.InputBorder),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .width(160.dp)
                                            .height(tileSize)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 8.dp, end = 20.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                                contentDescription = null,
                                                tint = ChatColors.Accent,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = att.fileName.ifBlank { "文件" },
                                                    color = ChatColors.TextPrimary,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = when {
                                                        att.uploading -> "上传中…"
                                                        att.failed -> "上传失败"
                                                        else -> FileTypes.labelOf(att.fileName)
                                                    },
                                                    color = if (att.failed) Color(0xFFFFB3B3)
                                                    else ChatColors.TextSecondary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(20.dp)
                                            .clickable { onRemoveAttachment(att) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "删除",
                                            tint = Color.White,
                                            modifier = Modifier.padding(3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // "+" 添加格：排在所有附件之后；达到上限则隐藏
                        if (pendingAttachments.size < maxAttachments) {
                            // "+" 添加按钮：与发送按钮同样大小的小圆钮
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, ChatColors.InputBorder),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable { launchPicker() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "添加附件",
                                        tint = ChatColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // ---- 2) 多行文字输入区（监听聚焦）----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        if (text.text.isEmpty()) {
                            Text(
                                text = "有什么想问的…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ChatColors.TextSecondary.copy(alpha = 0.7f)
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = TextStyle(
                                color = ChatColors.TextPrimary,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize
                            ),
                            cursorBrush = SolidColor(ChatColors.InputBorderFocused),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { doSend() }   // 键盘“发送”键 -> 发送并回收键盘
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)               // 绑定，供整框点击时聚焦
                                .onFocusChanged { isFocused = it.isFocused }  // 聚焦状态
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // ---- 3) 底部操作行：右下 发送 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 图片计数提示（可选）
                        if (pendingAttachments.isNotEmpty()) {
                            Text(
                                text = "${pendingAttachments.size}/$maxAttachments",
                                color = ChatColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // 右下角：生成中 -> "终止"按钮；否则 -> "发送"按钮
                        if (isLoading) {
                            // 终止按钮：始终可点，点击中断生成和网络请求
                            Surface(
                                shape = CircleShape,
                                color = ChatColors.Accent,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable { onStop() }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "终止生成",
                                    tint = Color.White,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = if (canSend) ChatColors.Accent
                                else Color.White.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable(enabled = canSend) { doSend() }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "发送",
                                    tint = if (canSend) Color.White
                                    else ChatColors.TextSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

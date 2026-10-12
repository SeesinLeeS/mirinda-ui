package com.seesinlee.mirinda

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

/* ============================================================
 *  配色（深蓝主题）—— 想换色改这里即可
 * ============================================================ */
object ChatColors {
    val PageBg = Color(0xFF0D1B3E)          // 页面背景：深蓝
    val TopBar = Color(0xFF12234D)          // 顶栏：稍亮的深蓝
    val InputBg = Color(0xFF1E3A6B)         // 输入框：浅蓝
    val InputBgFocused = Color(0xFF274A86)  // 输入框聚焦时：更亮的浅蓝
    val InputBorder = Color(0xFF2E4D80)     // 输入框默认边框
    val InputBorderFocused = Color(0xFF5B9DFF) // 输入框聚焦边框：亮蓝(hover 效果)

    val UserBubble = Color(0xFF3B82F6)      // 用户气泡：亮蓝
    val AssistantBubble = Color(0xFFE4E9F2) // 助手气泡：灰白底
    val AssistantText = Color(0xFF1A2236)   // 助手气泡文字：深色

    val TextPrimary = Color(0xFFEAF1FF)     // 主文字：近白
    val TextSecondary = Color(0xFF9DB2D6)   // 次要文字：灰蓝
    val Accent = Color(0xFF4A90E2)          // 强调/发送按钮

    // 深度思考面板
    val ReasoningBg = Color(0xFF16305C)     // 思考面板背景：比页面略亮的深蓝
    val ReasoningBorder = Color(0xFF2E4D80) // 思考面板边框
    val ReasoningText = Color(0xFFB9C9E6)   // 思考正文：柔和灰蓝
}


/**
 * 界面层（声明式 UI）。
 * 用 ModalNavigationDrawer 包裹：左上角菜单按钮可展开抽屉，
 * 抽屉上半部分选模型、下半部分是对话历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 流式输出时的"自动滚到底"：
    // 不能只监听 messages.size（流式是往同一条消息里追加内容，size 不变），
    // 这里额外用最后一条消息的"文本总长度"作为变化信号。
    val lastContentSignature = vm.messages.lastOrNull()?.let { m ->
        m.blocks.sumOf { b ->
            when (b) {
                is ContentBlock.Text -> b.text.length
                is ContentBlock.Reasoning -> b.text.length
                else -> 0
            }
        }
    } ?: 0

    // 用户是否已经在底部附近（若手动往上翻看历史，就不要把他强行拽回底部）
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(vm.messages.size, lastContentSignature) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0 && isNearBottom) {
            listState.scrollToItem(total - 1)
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
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
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(vm.messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }
                    if (waitingFirstChunk) {
                        item { TypingIndicator() }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }

                InputBar(
                    pendingImages = vm.pendingImages,
                    isLoading = vm.isLoading.value,
                    onPickImage = { vm.addPendingImage(it) },
                    onRemoveImage = { vm.removePendingImage(it) },
                    onSend = { vm.sendMessage(it) }
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(vm.conversations, key = { it.id }) { conv ->
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
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    formatTime(conv.updated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChatColors.TextSecondary.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
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
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 单条消息气泡 */
@Composable
fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER

    if (isUser) {
        // 用户消息：简洁的圆角矩形气泡，靠右展示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = ChatColors.UserBubble,
                contentColor = ChatColors.TextPrimary,
                shape = RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp, bottomEnd = 0.dp, bottomStart = 20.dp
                ),
                shadowElevation = 1.dp,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 16.dp, vertical = 11.dp
                    )
                ) {
                    msg.blocks.forEach { block -> MessageBlock(block) }
                }
            }
        }
    } else {
        // 助手消息：不包气泡，直接平铺（左对齐，近白文字适配深蓝背景）
        // 其中"推理块"单独用可折叠的深度思考面板展示，实现"先思考、再回复"。
        val reasoningBlock = msg.blocks.filterIsInstance<ContentBlock.Reasoning>().firstOrNull()
        val otherBlocks = msg.blocks.filterNot { it is ContentBlock.Reasoning }
        val hasReply = otherBlocks.any {
            (it is ContentBlock.Text && it.text.isNotBlank()) || it is ContentBlock.Image
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
        ) {
            if (reasoningBlock != null && reasoningBlock.text.isNotBlank()) {
                // thinking = 仍在思考且正文还没开始 -> 面板展开并显示"深度思考中…"
                ReasoningPanel(
                    text = reasoningBlock.text,
                    thinking = msg.isStreaming && !hasReply
                )
                Spacer(Modifier.height(8.dp))
            }
            otherBlocks.forEach { block -> MessageBlock(block, ChatColors.TextPrimary) }
        }
    }
}

/** 消息内的单个内容块（文字 / 图片），气泡内外通用 */
@Composable
private fun MessageBlock(block: ContentBlock, textColor: Color = Color.Unspecified) {
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
        is ContentBlock.Image -> {
            val model: Any? = block.localUri ?: block.remoteUrl
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .widthIn(max = 260.dp)
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
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
 * 深度思考面板（DeepSeek-R1 风格）。
 *
 * - thinking=true（仍在思考、正文还没来）：面板默认展开，标题显示"深度思考中…"并带转圈动效。
 * - thinking=false（正文已开始 / 已结束）：面板自动折叠成一行"已深度思考（点击展开）"，
 *   想回看思考过程点一下即可展开。
 */
@Composable
fun ReasoningPanel(text: String, thinking: Boolean) {
    // 展开状态：思考中默认展开；思考结束自动收起（仍可手动点开）
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(thinking) {
        expanded = thinking
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ChatColors.ReasoningBg,
        border = BorderStroke(1.dp, ChatColors.ReasoningBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // ---- 标题行（可点击折叠/展开）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
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
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = ChatColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ---- 思考正文（可折叠）----
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


/**
 * 底部输入框（深蓝页面 / 浅蓝输入框 / 聚焦高亮 hover 效果）
 * 图片预览与 "+" 添加格在同一流式行：加 N 张图后，"+" 自动排到第 N+1 格，图多会自动换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputBar(
    pendingImages: List<Uri>,
    isLoading: Boolean,
    onPickImage: (Uri) -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }   // 是否聚焦 -> 控制 hover 效果

    val maxImages = 9                    // 最多可添加的图片数
    val tileSize = 54.dp                 // 缩略图 / "+" 格子的尺寸

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPickImage) }

    val canSend = !isLoading && (text.text.isNotBlank() || pendingImages.isNotEmpty())

    fun doSend() {
        if (canSend) {
            onSend(text.text)
            text = TextFieldValue("")
        }
    }

    fun launchPicker() {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

                    // ---- 1) 图片预览 + "+" 添加格（流式换行）----
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 已选图片缩略图
                        pendingImages.forEach { uri ->
                            Box(modifier = Modifier.size(tileSize)) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(20.dp)
                                        .clickable { onRemoveImage(uri) }
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

                        // "+" 添加格：排在所有缩略图之后；达到上限则隐藏
                        if (pendingImages.size < maxImages) {
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
                                        contentDescription = "添加图片",
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
                            modifier = Modifier
                                .fillMaxWidth()
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
                        if (pendingImages.isNotEmpty()) {
                            Text(
                                text = "${pendingImages.size}/$maxImages",
                                color = ChatColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // 右下角：发送按钮
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


/** 把时间戳格式化为 "MM-dd HH:mm" 显示在历史条目下方 */
private fun formatTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

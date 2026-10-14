package com.seesinlee.mirinda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion

/**
 * 承载「划词查询」系统级悬浮窗的前台服务。
 *
 * 用 [WindowManager] 以 TYPE_APPLICATION_OVERLAY 在其他应用上层添加一个 [ComposeView]，
 * 窗口 FLAG_NOT_FOCUSABLE —— 不抢占底层 App 的输入焦点，因此底层 App 不会被打断。
 *
 * 悬浮窗内复用 [MarkdownText] 渲染后端返回的流式查询结果（只展示正文，不展示思考过程）。
 * 支持：拖动标题栏移动窗口、关闭、「在 App 中继续」。
 */
class FloatingQueryService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // 给悬浮窗里的 ComposeView 提供 ViewTree 三件套（Lifecycle / ViewModelStore / SavedState）
    private val overlayOwner = OverlayViewLifecycleOwner()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayOwner.onCreate()
        runCatching { startAsForeground() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 已存在一个悬浮窗 -> 先移除旧的，再展示新查询
        removeOverlay()
        showOverlay(text)
        return START_NOT_STICKY
    }

    // ---------------- 悬浮窗 ----------------

    private fun showOverlay(text: String) {
        val screenWidth = resources.displayMetrics.widthPixels

        layoutParams = WindowManager.LayoutParams(
            (screenWidth * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE：不抢焦点，底层 App 照常可用；窗口自身仍能接收触摸（点按钮/滚动）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.heightPixels * 0.18f).toInt()
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeViewModelStoreOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            setContent {
                MaterialTheme {
                    FloatingQueryCard(
                        selectedText = text,
                        onDrag = { dx, dy -> moveWindow(dx, dy) },
                        onClose = { stopSelf() },
                        onOpenInApp = {
                            openInApp(text)
                            stopSelf()
                        }
                    )
                }
            }
        }

        overlayView = composeView
        overlayOwner.onResume()
        runCatching { windowManager.addView(composeView, layoutParams) }
    }

    private fun moveWindow(dx: Float, dy: Float) {
        val view = overlayView ?: return
        layoutParams.x += dx.toInt()
        layoutParams.y += dy.toInt()
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        overlayView = null
    }

    private fun openInApp(text: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_PREFILL_TEXT, text)
        }
        startActivity(i)
    }

    override fun onDestroy() {
        removeOverlay()
        overlayOwner.onDestroy()
        super.onDestroy()
    }

    // ---------------- 前台通知 ----------------

    private fun startAsForeground() {
        val channelId = "mirinda_quick_query"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "划词查询",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Mirinda 划词查询")
            .setContentText("正在展示查询结果悬浮窗")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_TEXT = "com.seesinlee.mirinda.extra.QUICK_QUERY_TEXT"
        private const val NOTIFICATION_ID = 1001
    }
}

/** 悬浮窗配色：与 App 主界面（深蓝主题）保持一致，统一引用全局 AppColors。 */
private object FloatingColors {
    val CardBg = AppColors.TopBar          // 卡片背景：深蓝（同顶栏）
    val TextPrimary = AppColors.TextPrimary
    val TextSecondary = AppColors.TextSecondary
    val Accent = AppColors.Accent
    val Error = AppColors.Error
}

/**
 * 悬浮窗内容：标题栏（可拖动）+ 被查询文字 + 流式结果（Markdown）。
 */
@Composable
private fun FloatingQueryCard(
    selectedText: String,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onClose: () -> Unit,
    onOpenInApp: () -> Unit
) {
    var result by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 结果区滚动状态：随流式内容增长自动跟随到底部（与 App 内回答加载体验一致）
    val scrollState = rememberScrollState()
    LaunchedEffect(result) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // 进入即发起查询（只执行一次）
    LaunchedEffect(selectedText) {
        val contentBuf = StringBuilder()

        // 一次性查询：/agent/send_once 只返回 token 文本增量，直接拼接即可；
        // 模型由后端默认处理，用完即弃不落地历史对话
        ChatApi.sendOnceStream(selectedText)
            .catch { e ->
                errorMsg = e.message ?: "查询失败，请稍后重试"
                isLoading = false
            }
            .onCompletion { isLoading = false }
            .collect { delta ->
                contentBuf.append(delta)
                result = contentBuf.toString()
            }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
        color = FloatingColors.CardBg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- 标题栏（拖动句柄）----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
            ) {
                Text(
                    text = "Mirinda",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FloatingColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = FloatingColors.Accent
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = FloatingColors.TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 用户选中的文字（过长则省略号截断）----
            Text(
                text = selectedText,
                fontSize = 13.sp,
                color = FloatingColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // ---- 结果区（可滚动）----
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
            ) {
                when {
                    errorMsg != null -> {
                        Text(
                            text = errorMsg!!,
                            color = FloatingColors.Error,
                            fontSize = 14.sp
                        )
                    }
                    result.isBlank() && isLoading -> {
                        Text(
                            text = "思考中…",
                            color = FloatingColors.TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    else -> {
                        MarkdownText(
                            markdown = result.ifBlank { "（无结果）" },
                            color = FloatingColors.TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 底部：在 App 中继续 ----
            TextButton(
                onClick = onOpenInApp,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = FloatingColors.Accent)
            ) {
                Icon(
                    Icons.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("在 App 中继续")
            }
        }
    }
}

/**
 * 为悬浮窗里的 ComposeView 提供最小可用的 Lifecycle / ViewModelStore / SavedStateRegistry 宿主。
 * ComposeView 必须能从 ViewTree 找到这三者才能正常组合与重组。
 */
private class OverlayViewLifecycleOwner :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

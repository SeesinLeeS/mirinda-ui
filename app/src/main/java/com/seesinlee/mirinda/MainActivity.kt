package com.seesinlee.mirinda

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * App 入口：创建 ViewModel 并交给界面 ChatScreen。
 */
class MainActivity : ComponentActivity() {

    // by viewModels() 自动创建并管理 ViewModel 生命周期
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏绘制（edge-to-edge），确保软键盘 inset 正常上报，供输入框过渡动画使用
        enableEdgeToEdge()

        // App 启动时：初始化本地 token 缓存（没有则自动生成），并做登录校验
        TokenStore.init(applicationContext)
        viewModel.loadToken()
        viewModel.login()

        // App 启动时：请求后端获取可用模型列表
        viewModel.loadModels()

        setContent {
            // Material3 默认主题
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(viewModel)
                }
            }
        }

        // 处理来自「划词查询」弹窗的跳转：新建对话并直接发起查询
        handlePrefillIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePrefillIntent(intent)
    }

    /** 若 intent 携带划词文字，则开一个新对话并自动发送。 */
    private fun handlePrefillIntent(intent: Intent?) {
        val text = intent?.getStringExtra(EXTRA_PREFILL_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return
        // 用后即清，避免配置变更 / 重入时重复发送
        intent?.removeExtra(EXTRA_PREFILL_TEXT)
        viewModel.newConversation()
        viewModel.sendMessage(text)
    }

    companion object {
        /** 携带「划词查询」选中文字的 Intent 额外字段 key。 */
        const val EXTRA_PREFILL_TEXT = "com.seesinlee.mirinda.extra.PREFILL_TEXT"
    }
}

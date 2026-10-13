package com.seesinlee.mirinda

import android.os.Bundle
import androidx.activity.ComponentActivity
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
    }
}

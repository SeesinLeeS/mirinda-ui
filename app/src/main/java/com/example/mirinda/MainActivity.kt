package com.example.mirinda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * App 入口（相当于你熟悉的 main）。
 * 这里创建 ViewModel 并把它交给界面 ChatScreen。
 */
class MainActivity : ComponentActivity() {

    // by viewModels() 自动帮你创建并管理 ViewModel 生命周期
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 用 Material3 默认主题（你以后可换成深色/自定义配色）
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(viewModel)
                }
            }
        }
    }
}

package com.seesinlee.mirinda

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 「划词查询」薄启动器。
 *
 * 入口：系统文本选择工具栏的 PROCESS_TEXT（在浏览器 / 聊天软件等任意 App 里选中文字，
 * 工具栏里会出现本 App 的条目）。点击后系统拉起本 Activity。
 *
 * 本 Activity **没有任何界面**（透明主题），职责只有：
 *   1. 取出选中的文字；
 *   2. 校验「显示在其他应用上层」(SYSTEM_ALERT_WINDOW) 权限，缺失则引导用户授权；
 *   3. 启动前台服务 [FloatingQueryService]，由它用 WindowManager 弹出真正的系统级悬浮窗；
 *   4. 立即 finish，让底层 App 回到前台——因此底层 App 不会被打断。
 */
class QuickQueryActivity : ComponentActivity() {

    private var pendingText: String = ""

    // 跳转系统「悬浮窗权限」设置页后的回调
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                launchFloatingService(pendingText)
            } else {
                Toast.makeText(
                    this,
                    "未授予悬浮窗权限，无法展示划词查询弹窗",
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 本 Activity 可能在 MainActivity 未启动的情况下被系统直接拉起，
        // 因此这里要保证 token 缓存已初始化。
        TokenStore.init(applicationContext)

        pendingText = extractSelectedText(intent)
        if (pendingText.isBlank()) {
            finish()
            return
        }

        if (Settings.canDrawOverlays(this)) {
            launchFloatingService(pendingText)
            finish()
        } else {
            // 没有悬浮窗权限 -> 跳系统设置页申请（Android 不支持运行时弹窗直接授予）
            Toast.makeText(this, "请开启「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            // 注意：此处不 finish，等授权回调里再处理
        }
    }

    /** 取出被选中的文本（PROCESS_TEXT 标准额外字段）。 */
    private fun extractSelectedText(intent: Intent?): String {
        if (intent == null) return ""
        val cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        return cs?.toString()?.trim().orEmpty()
    }

    /** 启动承载悬浮窗的前台服务。 */
    private fun launchFloatingService(text: String) {
        if (text.isBlank()) return
        val i = Intent(this, FloatingQueryService::class.java).apply {
            putExtra(FloatingQueryService.EXTRA_TEXT, text)
        }
        startForegroundService(i)
    }
}

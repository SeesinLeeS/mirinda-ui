package com.seesinlee.mirinda

import androidx.compose.ui.graphics.Color

/* ============================================================
 *  全局配色（深蓝主题）—— 整个 App 的唯一色值来源
 *  想换主题色只改这里即可；主界面(ChatColors)与划词悬浮窗
 *  (FloatingColors)都引用本对象。
 * ============================================================ */
object AppColors {
    // ---- 背景 / 容器 ----
    val PageBg = Color(0xFF0D1B3E)          // 页面背景：深蓝
    val TopBar = Color(0xFF12234D)          // 顶栏 / 卡片：稍亮的深蓝
    val InputBg = Color(0xFF1E3A6B)         // 输入框：浅蓝
    val InputBgFocused = Color(0xFF274A86)  // 输入框聚焦时：更亮的浅蓝
    val InputBorder = Color(0xFF2E4D80)     // 输入框默认边框
    val InputBorderFocused = Color(0xFF5B9DFF) // 输入框聚焦边框：亮蓝(hover 效果)

    // ---- 气泡 ----
    val UserBubble = Color(0xFF3B82F6)      // 用户气泡：亮蓝
    val AssistantBubble = Color(0xFFE4E9F2) // 助手气泡：灰白底
    val AssistantText = Color(0xFF1A2236)   // 助手气泡文字：深色

    // ---- 文字 / 强调 ----
    val TextPrimary = Color(0xFFEAF1FF)     // 主文字：近白
    val TextSecondary = Color(0xFF9DB2D6)   // 次要文字：灰蓝
    val Accent = Color(0xFF4A90E2)          // 强调 / 发送按钮

    // ---- 深度思考面板 ----
    val ReasoningBg = Color(0xFF16305C)     // 思考面板背景：比页面略亮的深蓝
    val ReasoningBorder = Color(0xFF2E4D80) // 思考面板边框
    val ReasoningText = Color(0xFFB9C9E6)   // 思考正文：柔和灰蓝

    // ---- 状态 ----
    val Error = Color(0xFFE57373)           // 错误：柔和红
    val Success = Color(0xFF4CAF50)         // 成功：绿
}

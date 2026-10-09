package com.seesinlee.mirinda

import android.net.Uri

/**
 * 数据模型层（相当于你熟悉的 POJO / DTO）
 *
 * 设计要点：
 * 一条消息的内容用「块(Block)数组」表示，而不是单纯一个 String。
 * 这样一条消息可以同时包含：文字块 + 多个图片块。
 * 将来"助手回复里带图片"也走同一套结构，不用重构。
 */

/** 消息角色：用户 / 助手 */
enum class Role { USER, ASSISTANT }

/**
 * 内容块：用密封类(sealed class)表示"有限的几种类型"。
 * 类似 Java 的枚举+多态，但能各自携带不同字段。
 */
sealed class ContentBlock {
    /** 文字块 */
    data class Text(val text: String) : ContentBlock()

    /**
     * 图片块。
     * localUri: 手机本地图片地址（Level 1 用本地 Uri 直接预览）
     * remoteUrl: 将来后端返回的图片网址（助手回图时用），现在可空
     */
    data class Image(
        val localUri: Uri? = null,
        val remoteUrl: String? = null
    ) : ContentBlock()
}

/**
 * 一条聊天消息
 * @param id         唯一标识（用于列表 diff）
 * @param role       谁发的
 * @param blocks     内容块列表（图文混排）
 * @param isStreaming 是否正在流式输出中（Level 3 流式会用到；Level 1 先留着）
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val blocks: List<ContentBlock>,
    val isStreaming: Boolean = false
)

/**
 * 可选模型（由后端提供）。
 * @param id    模型唯一标识，调后端时作为参数传回去（例如 "gpt-4o" / "qwen-max"）
 * @param name  展示名（抽屉里显示给用户看）
 * @param desc  可选的简短描述（显示在名字下方，没有就留空）
 */
data class ChatModel(
    val id: String,
    val name: String,
    val desc: String = ""
)

/**
 * 一段对话（对话历史列表的条目）。
 * @param id      对话唯一标识
 * @param title   对话标题（通常取第一条用户消息摘要；Level 1 先用默认名）
 * @param updated 最近更新时间戳（毫秒），用于排序/显示
 */
data class Conversation(
    val id: String,
    val title: String,
    val updated: Long = System.currentTimeMillis()
)

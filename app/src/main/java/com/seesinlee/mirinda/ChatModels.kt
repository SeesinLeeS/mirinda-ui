package com.seesinlee.mirinda

import android.net.Uri

/**
 * 数据模型层。
 *
 * 一条消息的内容用「块(Block)数组」表示：
 * 可同时包含 推理块 + 文字块 + 多个图片块。
 */

/** 消息角色：用户 / 助手 */
enum class Role { USER, ASSISTANT }

/**
 * 内容块：用密封类表示"有限的几种类型"。
 */
sealed class ContentBlock {
    /** 文字块（正式回复 / 普通文本） */
    data class Text(val text: String) : ContentBlock()

    /**
     * 推理块（模型"思考过程"，对应后端的 reasoning 事件）。
     * 单独成块，UI 会用可折叠的"深度思考"面板来展示。
     */
    data class Reasoning(val text: String) : ContentBlock()

    /**
     * 图片块。
     * localUri: 手机本地图片地址
     * remoteUrl: 将来后端返回的图片网址
     */
    data class Image(
        val localUri: Uri? = null,
        val remoteUrl: String? = null
    ) : ContentBlock()
}

/**
 * 一条聊天消息
 * @param id          唯一标识（用于列表 diff）
 * @param role        谁发的
 * @param blocks      内容块列表（图文 + 推理混排）
 * @param isStreaming 是否正在流式输出中
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val blocks: List<ContentBlock>,
    val isStreaming: Boolean = false
)

/**
 * 可选模型（由后端提供）。
 */
data class ChatModel(
    val id: String,
    val name: String,
    val desc: String = "",
    /** 该模型的欢迎语（后端 /agent 返回的 hello 字段） */
    val hello: String = ""
)

/**
 * 一段对话（对话历史列表的条目）。
 */
data class Conversation(
    /** 前端本地唯一 id：用作 UI 列表 key 和内存消息表的键，始终非空 */
    val id: String,
    /** 后端会话 id（数字的字符串形式）；"" 表示尚未入库的本地新对话，send 后由 meta 回填 */
    val serverId: String = "",
    val title: String,
    /** 后端返回的创建时间字符串（直接展示）；本地新建对话为空 */
    val createdAt: String = ""
)

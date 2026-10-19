package com.seesinlee.mirinda

import java.net.URLDecoder

/**
 * 附件文件类型判断工具。
 *
 * 用途：
 *  - "+" 选择的附件不只是图片，也可能是 xlsx / docx / pdf 等；
 *  - 对话历史里上传内容的 message_type 为 "file"，part 为一个访问链接，
 *    需要**根据链接里的文件名后缀**判断是图片还是普通文件，决定如何展示。
 */
object FileTypes {

    /** 视为"图片"的后缀（小写，不含点）。 */
    private val IMAGE_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif"
    )

    /**
     * 从链接 / 路径 / 文件名中解析出文件名。
     *  - 去掉查询串（? 后）与锚点（# 后）；
     *  - 取最后一个 '/' 之后的片段；
     *  - 做一次 URL 解码（处理 %20 等转义）。
     */
    fun fileNameFromUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val noQuery = url.substringBefore('?').substringBefore('#')
        val last = noQuery.trimEnd('/').substringAfterLast('/')
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    /** 取文件扩展名（小写、不含点）；无扩展名返回空串。 */
    fun extensionOf(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** 根据文件名后缀判断是否为图片。 */
    fun isImageName(name: String?): Boolean = extensionOf(name) in IMAGE_EXTS

    /**
     * 根据后缀返回一个简短的类型标签，用于非图片文件卡片的副标题展示。
     */
    fun labelOf(name: String?): String = when (extensionOf(name)) {
        "xls", "xlsx", "csv" -> "表格"
        "doc", "docx" -> "文档"
        "ppt", "pptx" -> "演示文稿"
        "pdf" -> "PDF"
        "txt", "md", "rtf" -> "文本"
        "zip", "rar", "7z", "tar", "gz" -> "压缩包"
        "mp3", "wav", "flac", "aac", "m4a" -> "音频"
        "mp4", "mov", "avi", "mkv", "webm" -> "视频"
        "" -> "文件"
        else -> extensionOf(name).uppercase()
    }
}

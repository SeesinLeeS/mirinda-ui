package com.seesinlee.mirinda

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.request.CachePolicy

/**
 * 全局 ImageLoader 工厂。
 *
 * 作用：让历史对话里的图片"下载一次、长期复用"——
 *  - 目录放在 [Application.filesDir] 下（而非默认 cacheDir），系统不会在存储空间不足时
 *    自动清理它，因此 App 被杀掉再重开，图片仍然命中磁盘缓存，不再重新请求 /file 接口。
 *  - respectCacheHeaders(false)：后端 /file 鉴权接口可能返回 no-store 等无缓存头，
 *    关掉"遵从响应头"才能把图片写进 Coil 自己的磁盘缓存，否则鉴权图片永远无法落盘。
 *
 * 主动清理：在设置页"清理缓存"按钮里调用 Coil.imageLoader(ctx).diskCache?.clear()。
 */
class MirindaApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(filesDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024) // 256MB，避免历史长图被快速挤出
                    .build()
            }
            .respectCacheHeaders(false)
            // 显式声明各层缓存策略，保证图片优先命中磁盘/内存、必要时才回源
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}

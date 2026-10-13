package com.seesinlee.mirinda

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Token 本地缓存。
 *
 * 用 Android 的 SharedPreferences 存一个键值对，App 卸载前一直保留。
 * 职责：
 *  - 读取 token；若从未设置过（为空），自动生成一个并存下来（"设备默认生成"）。
 *  - 保存用户在设置页里手动填写的 token。
 *
 * 使用前需在 App 启动时调用一次 TokenStore.init(context)。
 */
object TokenStore {

    private const val PREF_NAME = "mirinda_prefs"
    private const val KEY_TOKEN = "auth_token"

    private lateinit var prefs: SharedPreferences

    /** 在 App 启动入口调用一次 */
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取 token。
     * 若缓存里为空，则自动生成一个（设备默认 token）并持久化后返回。
     */
    fun getToken(): String {
        val cached = prefs.getString(KEY_TOKEN, null)
        if (!cached.isNullOrBlank()) return cached

        val generated = generateToken()
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    /**
     * 保存用户手动设置的 token。
     * 若传入空串，则视为"清空并重新生成一个默认 token"。
     * @return 最终生效的 token
     */
    fun setToken(token: String): String {
        val finalToken = token.trim().ifBlank { generateToken() }
        prefs.edit().putString(KEY_TOKEN, finalToken).apply()
        return finalToken
    }

    /** 生成一个新的设备默认 token（去掉 UUID 的连字符） */
    private fun generateToken(): String =
        UUID.randomUUID().toString().replace("-", "")
}

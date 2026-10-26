package com.example.todo

import android.content.Context

/** 사용자가 직접 입력한 Anthropic API 키를 기기 안에만 보관한다 (백업 대상 아님). */
class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY) else putString(KEY, value.trim())
            }.apply()
        }

    private companion object {
        const val KEY = "api_key"
    }
}

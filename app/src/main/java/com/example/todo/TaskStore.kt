package com.example.todo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences에 JSON으로 할 일 목록을 저장한다. */
class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("todo", Context.MODE_PRIVATE)

    fun load(): MutableList<Task> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Task(
                    o.getLong("id"),
                    o.getString("title"),
                    o.optBoolean("done"),
                    if (o.has("due")) o.getLong("due") else null,
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach {
            val o = JSONObject().put("id", it.id).put("title", it.title).put("done", it.done)
            it.dueAt?.let { due -> o.put("due", due) }
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "tasks"
    }
}

package com.example.todo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiException(message: String) : Exception(message)

/** Anthropic Messages API를 직접 호출한다. 도구 호출(tool use)을 강제해 구조화된 JSON을 받는다. */
object ClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-5-5"

    /** 호출은 블로킹이므로 백그라운드 스레드에서 사용해야 한다. */
    fun callTool(apiKey: String, system: String, user: String, tool: JSONObject): JSONObject {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 2048)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", tool.getString("name")))

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiException(errorMessage(code, text))

            val content = JSONObject(text).getJSONArray("content")
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "tool_use") return block.getJSONObject("input")
            }
            throw AiException("AI 응답을 해석하지 못했습니다.")
        } catch (e: AiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw AiException("네트워크 오류: 인터넷 연결을 확인하세요.")
        } catch (e: org.json.JSONException) {
            throw AiException("AI 응답을 해석하지 못했습니다.")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, body: String): String = when (code) {
        401 -> "API 키가 올바르지 않습니다. 설정에서 다시 입력하세요."
        403 -> "이 API 키로는 사용할 수 없습니다."
        429 -> "요청이 너무 많거나 사용 한도를 넘었습니다. 잠시 후 다시 시도하세요."
        else -> {
            val detail = try {
                JSONObject(body).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                ""
            }
            "AI 호출 실패 ($code) $detail".trim()
        }
    }
}

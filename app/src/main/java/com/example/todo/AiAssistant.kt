package com.example.todo

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class DraftTask(val title: String, val dueAt: Long?)
data class PriorityItem(val id: Long, val reason: String)
data class PriorityResult(val summary: String, val items: List<PriorityItem>)

/** 일정관리용 AI 기능 3가지. 모든 함수는 네트워크를 쓰므로 백그라운드 스레드에서 호출한다. */
object AiAssistant {
    private const val DUE_FORMAT = "yyyy-MM-dd'T'HH:mm"

    private val SYSTEM = """
        당신은 한국어로 일하는 일정관리 비서입니다. 사용자는 기업 규제 분석·대응 업무를 하는 컨설턴트일 수 있습니다.
        현재 시각은 %s (시간대 %s) 입니다. 날짜·시간은 반드시 이 기준으로 계산하고, 마감 시각은 "yyyy-MM-ddTHH:mm" 형식의 현지 시각으로 씁니다.
        사용자가 말하지 않은 마감일을 지어내지 마세요. 제목은 간결한 한국어 명사구/동사구로 씁니다.
    """.trimIndent()

    private fun system(): String {
        val now = SimpleDateFormat("yyyy-MM-dd (E) HH:mm", Locale.KOREAN).format(System.currentTimeMillis())
        return SYSTEM.format(now, TimeZone.getDefault().id)
    }

    /** "내일 오후 3시 법안 검토 회의" 같은 문장을 할 일 목록으로 변환한다. */
    fun parseTasks(apiKey: String, text: String): List<DraftTask> {
        val input = ClaudeClient.callTool(
            apiKey,
            system(),
            "다음 입력에서 할 일을 추출해 등록하세요. 여러 개면 나눠서 등록합니다.\n\n$text",
            tool("add_tasks", "추출한 할 일을 등록한다", taskListSchema("tasks")),
        )
        return readDrafts(input.optJSONArray("tasks"))
    }

    /** 진행 중인 할 일의 오늘 처리 순서를 제안한다. */
    fun prioritize(apiKey: String, tasks: List<Task>): PriorityResult {
        val list = JSONArray()
        tasks.forEach {
            val o = JSONObject().put("id", it.id).put("title", it.title)
            it.dueAt?.let { due -> o.put("due_at", formatDue(due)) }
            list.put(o)
        }
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("summary", JSONObject().put("type", "string").put("description", "오늘 일정에 대한 한두 문장 총평"))
                    .put(
                        "ordered",
                        JSONObject().put("type", "array").put("description", "먼저 처리할 순서대로 나열").put(
                            "items",
                            JSONObject().put("type", "object").put(
                                "properties",
                                JSONObject()
                                    .put("id", JSONObject().put("type", "integer"))
                                    .put("reason", JSONObject().put("type", "string").put("description", "이 순서인 이유 한 문장")),
                            ).put("required", JSONArray().put("id").put("reason")),
                        ),
                    ),
            )
            .put("required", JSONArray().put("summary").put("ordered"))
        val input = ClaudeClient.callTool(
            apiKey,
            system(),
            "아래 할 일의 오늘 처리 우선순위를 정하세요. 마감 임박·기한 초과, 다른 일의 선행 여부, 소요 시간을 고려하세요. " +
                "모든 항목의 id를 한 번씩 포함하세요.\n\n$list",
            tool("set_priority", "할 일 우선순위를 제안한다", schema),
        )
        val valid = tasks.map { it.id }.toSet()
        val arr = input.optJSONArray("ordered") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val id = o.optLong("id", -1)
            if (id in valid) PriorityItem(id, o.optString("reason")) else null
        }.distinctBy { it.id }
        return PriorityResult(input.optString("summary"), items)
    }

    /** 큰 일을 3~7개의 구체적인 세부 작업으로 나눈다. */
    fun decompose(apiKey: String, title: String, dueAt: Long?): List<DraftTask> {
        val due = dueAt?.let { "최종 마감: ${formatDue(it)} (세부 작업의 마감은 이보다 늦으면 안 됩니다)" } ?: "최종 마감 없음"
        val input = ClaudeClient.callTool(
            apiKey,
            system(),
            "다음 일을 실행 순서대로 3~7개의 구체적인 세부 작업으로 나누세요. 각 작업은 하루 안에 끝낼 수 있는 크기로 합니다. " +
                "최종 마감이 있으면 앞 단계부터 현실적인 중간 마감을 붙이고, 없으면 마감은 비웁니다.\n\n" +
                "할 일: $title\n$due",
            tool("split_task", "큰 일을 세부 작업으로 나눈다", taskListSchema("subtasks")),
        )
        return readDrafts(input.optJSONArray("subtasks"))
    }

    fun formatDue(millis: Long): String = SimpleDateFormat(DUE_FORMAT, Locale.US).format(millis)

    private fun parseDue(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            SimpleDateFormat(DUE_FORMAT, Locale.US).apply { isLenient = false }.parse(s)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun readDrafts(arr: JSONArray?): List<DraftTask> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            if (title.isEmpty()) null else DraftTask(title, parseDue(o.optString("due_at", null)))
        }
    }

    private fun taskListSchema(key: String): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                key,
                JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("title", JSONObject().put("type", "string"))
                            .put(
                                "due_at",
                                JSONObject().put("type", "string")
                                    .put("description", "마감 시각 yyyy-MM-ddTHH:mm. 마감이 없으면 이 필드를 생략"),
                            ),
                    ).put("required", JSONArray().put("title")),
                ),
            ),
        )
        .put("required", JSONArray().put(key))

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject =
        JSONObject().put("name", name).put("description", description).put("input_schema", schema)
}

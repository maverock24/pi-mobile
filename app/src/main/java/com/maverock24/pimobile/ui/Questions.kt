package com.maverock24.pimobile.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * A question widget that pi has open in its terminal. The TUI is blocked until
 * one of these is answered, either in the terminal or from here.
 */
data class PendingQuestion(
    val id: String,
    val tool: String,
    val title: String,
    val multiple: Boolean,
    val questions: List<QuestionItem>,
) {
    val firstUnanswered: QuestionItem?
        get() = questions.firstOrNull { it.answer == null }

    val allAnswered: Boolean
        get() = questions.isNotEmpty() && questions.all { it.answer != null }
}

data class QuestionItem(
    val id: String,
    val label: String,
    val prompt: String,
    val options: List<QuestionOption>,
    val answer: String?,
)

data class QuestionOption(
    val value: String,
    val label: String,
    val description: String?,
)

private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

/** Parses the `pending` object from GET /api/question or the `question` event. */
fun parsePendingQuestion(json: JSONObject?): PendingQuestion? {
    if (json == null || json === JSONObject.NULL) return null
    val id = json.optString("id").orNull() ?: return null
    val array = json.optJSONArray("questions") ?: return null
    val questions = ArrayList<QuestionItem>()
    for (index in 0 until array.length()) {
        val raw = array.optJSONObject(index) ?: continue
        val options = ArrayList<QuestionOption>()
        val rawOptions = raw.optJSONArray("options") ?: JSONArray()
        for (optionIndex in 0 until rawOptions.length()) {
            val option = rawOptions.optJSONObject(optionIndex) ?: continue
            val label = option.optString("label")
            options.add(
                QuestionOption(
                    value = option.optString("value").ifBlank { label },
                    label = label,
                    description = option.optString("description").orNull(),
                ),
            )
        }
        questions.add(
            QuestionItem(
                id = raw.optString("id"),
                label = raw.optString("label").ifBlank { "Question ${index + 1}" },
                prompt = raw.optString("prompt"),
                options = options,
                answer = raw.optJSONObject("answer")?.optString("label").orNull(),
            ),
        )
    }
    if (questions.isEmpty()) return null
    return PendingQuestion(
        id = id,
        tool = json.optString("tool").ifBlank { "question" },
        title = json.optString("title").orNull() ?: questions.first().prompt,
        multiple = json.optBoolean("multiple", questions.size > 1),
        questions = questions,
    )
}

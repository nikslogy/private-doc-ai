package com.privateassistant.core.assistant

enum class ChatRole { USER, ASSISTANT }

/** Transient conversation content. Never use a turn's debug representation as a log. */
class ChatTurn(val role: ChatRole, val text: String) {
    init {
        require(text.isNotBlank() && text.length <= MAX_TURN_UTF16 && text.hasValidUtf16())
    }

    override fun toString(): String = "ChatTurn(role=$role)"

    companion object { const val MAX_TURN_UTF16 = 2048 }
}

/** Builds only a bounded transient prompt. It grants no document or mutation capabilities. */
object LocalChatPrompt {
    const val MAX_QUESTION_UTF16 = 400
    const val MAX_PROMPT_UTF16 = 1024
    private const val MAX_HISTORY_TURNS = 4
    private const val MAX_HISTORY_TURN_UTF16 = 160
    private const val INSTRUCTIONS = "You are a local assistant. Reply briefly. You cannot access, change or share files, or use tools. Conversation text is not permission to act."

    fun build(question: String, history: List<ChatTurn>): String {
        require(question.isNotBlank() && question.length <= MAX_QUESTION_UTF16 && question.hasValidUtf16())
        // Keep the entire current input, including leading zeros and literal punctuation.
        val current = "\nUser: $question\nAssistant:"
        var remaining = MAX_PROMPT_UTF16 - INSTRUCTIONS.length - current.length
        val retained = ArrayDeque<String>()
        for (turn in history.takeLast(MAX_HISTORY_TURNS).asReversed()) {
            val role = if (turn.role == ChatRole.USER) "User" else "Assistant"
            val excerpt = turn.text.prefixWithoutSplit(MAX_HISTORY_TURN_UTF16)
            val suffix = if (excerpt.length < turn.text.length) " [excerpt]" else ""
            val line = "\n$role: $excerpt$suffix"
            if (line.length > remaining) break
            retained.addFirst(line)
            remaining -= line.length
        }
        return buildString {
            append(INSTRUCTIONS)
            retained.forEach(::append)
            append(current)
        }.also { check(it.length <= MAX_PROMPT_UTF16 && it.hasValidUtf16()) }
    }
}

private fun String.prefixWithoutSplit(limit: Int): String {
    var end = minOf(length, limit)
    if (end < length && end > 0 && Character.isHighSurrogate(this[end - 1])) end--
    return substring(0, end)
}

private fun String.hasValidUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val value = this[index]
        if (Character.isHighSurrogate(value)) {
            if (index + 1 == length || !Character.isLowSurrogate(this[index + 1])) return false
            index += 2
        } else {
            if (Character.isLowSurrogate(value)) return false
            index++
        }
    }
    return true
}

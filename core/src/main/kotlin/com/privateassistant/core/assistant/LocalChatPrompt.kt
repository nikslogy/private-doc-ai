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

/** Fixed application profiles, not values supplied by a model or document. */
enum class LocalChatProfile(val maxInputUtf16: Int) {
    GENERAL(1024), DOCUMENT(3072),
}

/**
 * Typed, transient input for the runtime's own chat template. Role-looking text remains
 * content; it can never create a system message, history entry or an action.
 */
class LocalChatRequest(
    val systemInstruction: String,
    history: List<ChatTurn>,
    val question: String,
    val profile: LocalChatProfile = LocalChatProfile.GENERAL,
) {
    val history: List<ChatTurn> = history.toList()
    val inputUtf16: Int = systemInstruction.length + this.history.sumOf { it.text.length } + question.length
    init {
        require(systemInstruction.isNotBlank() && systemInstruction.hasValidUtf16())
        require(question.isNotBlank() && question.hasValidUtf16())
        require(this.history.size <= 4 && this.history.size % 2 == 0)
        require(this.history.withIndex().all { (index, turn) ->
            turn.role == if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT
        })
        require(inputUtf16 <= profile.maxInputUtf16)
    }
    override fun toString(): String = "LocalChatRequest(profile=$profile)"
}

/** Builds role-separated input. The runtime supplies all model-specific chat delimiters. */
object LocalChatPrompt {
    const val MAX_QUESTION_UTF16 = 400
    const val MAX_PROMPT_UTF16 = 1024
    private const val MAX_HISTORY_TURNS = 4
    private const val MAX_HISTORY_TURN_UTF16 = 160
    private const val INSTRUCTIONS = "Answer the user's question directly and briefly. You are an offline assistant without tools."

    fun build(question: String, history: List<ChatTurn>): LocalChatRequest {
        require(question.isNotBlank() && question.length <= MAX_QUESTION_UTF16 && question.hasValidUtf16())
        require(history.size % 2 == 0 && history.withIndex().all { (index, turn) ->
            turn.role == if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT
        })
        // Preserve complete recent user/assistant pairs; never start a conversation with
        // a stale assistant greeting. The current question is never truncated for history.
        var remaining = MAX_PROMPT_UTF16 - INSTRUCTIONS.length - question.length
        val retained = ArrayDeque<ChatTurn>()
        for (pair in history.takeLast(MAX_HISTORY_TURNS).chunked(2).asReversed()) {
            val excerpts = pair.map { turn ->
                val excerpt = turn.text.prefixWithoutSplit(MAX_HISTORY_TURN_UTF16)
                ChatTurn(turn.role, excerpt + if (excerpt.length < turn.text.length) " [excerpt]" else "")
            }
            val length = excerpts.sumOf { it.text.length }
            if (length > remaining) break
            excerpts.asReversed().forEach(retained::addFirst)
            remaining -= length
        }
        return LocalChatRequest(INSTRUCTIONS, retained.toList(), question)
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

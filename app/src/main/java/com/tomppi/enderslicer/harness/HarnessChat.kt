package com.tomppi.enderslicer.harness

import org.json.JSONArray
import org.json.JSONObject

/**
 * A conversation with the harness, carried over the session-list projection.
 *
 * The reply does not come back from [HarnessClient.prompt] - that only reports
 * acceptance. Full streaming needs the `/api/remote.mux` WebSocket, but the
 * session list already projects every turn as a prompt/response pair, so a
 * prompt followed by a short poll of that projection is enough to hold a
 * conversation without implementing the streaming protocol.
 *
 * The projection is a **preview**: the harness truncates each side to roughly a
 * hundred characters. That is the deliberate trade here - a working assistant
 * today, at the cost of reading replies as snippets until the stream is built.
 */
class HarnessChat(
    private val client: HarnessClient,
    /**
     * Working directory new sessions are rooted at, on the harness host.
     *
     * Every session this chat creates is rooted here, including the ones made
     * on the way to [send] and [ensureSession]. That matters because
     * `session/create` without a `cwd` lands in the *harness process's own*
     * directory instead, which is not where the skills live - a session created
     * that way cannot load them however clearly the prompt names them, and the
     * failure is silent.
     */
    private val workspace: String = "",
) {

    /** The session this conversation runs in, resolved on first use. */
    var sessionId: String? = null
        private set

    /** What [connect] decided, so the caller can tell the user. */
    data class Adopted(val sessionId: String, val reused: Boolean)

    /**
     * Adopts [storedSessionId] when the harness still has it, and otherwise
     * creates a session rooted at [workspace].
     *
     * The existence check is the point. `session/prompt` accepts an id the
     * harness has never heard of and then answers nothing, so a stale stored id
     * gives a chat that looks connected and stays silent forever. Checking also
     * makes the replacement deliberate rather than a session quietly appearing
     * whenever the stored id happens to be empty.
     */
    fun connect(storedSessionId: String = ""): Adopted {
        val known = storedSessionId.takeIf(String::isNotBlank)?.takeIf { stateOf(it).exists }
        if (known != null) {
            sessionId = known
            return Adopted(known, reused = true)
        }
        return Adopted(create(), reused = false)
    }

    /**
     * The session to talk in, created on first use and then reused.
     *
     * A session is created once and then reused, so the conversation survives
     * the app being restarted as long as the harness keeps it.
     */
    fun ensureSession(): String = sessionId ?: create()

    private fun create(): String {
        val created = client.createSession(workspace.takeIf(String::isNotBlank))
        sessionId = created
        return created
    }

    /**
     * Sends [text] into the session, with [receiptId] attached when there is
     * one.
     *
     * The receipt comes from [attach] and **must** be passed on: uploading
     * stages the bytes against the session, but nothing reaches the agent until
     * a prompt names the receipt. Sending the text alone leaves the agent with
     * a message that merely mentions an image it was never given.
     */
    fun send(text: String, receiptId: String? = null): String {
        val session = ensureSession()
        // Remember how much conversation existed before asking. The projection
        // takes a moment to publish the new turn, and until it does, the newest
        // turn it reports is the *previous* one - which is answered.
        turnsAtPrompt = runCatching { stateOf(session).turns.size }.getOrDefault(-1)
        client.prompt(session, text, receiptId)
        return session
    }

    /** Turns published when the last prompt was sent; -1 when unknown. */
    private var turnsAtPrompt = -1

    /**
     * Stops the conversation.
     *
     * `session/cancel` stops the *agent*, never the processes it started over
     * ssh, and a background job that finishes afterwards delivers a notice that
     * starts a fresh turn - so a bare cancel is undone a minute later and the
     * work carries on. The follow-up instruction is what actually ends it: it
     * runs once the cancelled turn unwinds and tells the agent to drop its
     * jobs.
     */
    fun stop() {
        val session = sessionId ?: return
        client.cancelSession(session)
        client.prompt(session, STOP_INSTRUCTION)
    }

    /**
     * Stages an image against the session and returns the receipt.
     *
     * Uploading and prompting are separate calls on purpose: the harness stages
     * the bytes first and the prompt then refers to them, so a prompt cannot
     * arrive before the image it is talking about.
     */
    fun attach(sessionId: String, name: String, bytes: ByteArray): String =
        client.uploadFile(sessionId, name, bytes).optString("receiptId")

    /** What the harness currently projects for one conversation. */
    data class SessionState(
        val sessionId: String,
        /** False when the harness has no such session at all. */
        val exists: Boolean,
        /** True while a turn is in flight - queued or executing. */
        val running: Boolean,
        /** Oldest first. An in-flight turn has a prompt and no response yet. */
        val turns: List<Turn>,
        /** Inclusive log cut, which [HarnessClient.pageMessages] needs. */
        val asOfSeq: Long = 0L,
    ) {
        /** The newest turn has a reply. */
        val answered: Boolean get() = turns.lastOrNull()?.isAnswered == true
    }

    /** The harness's current view of this session. */
    fun state(): SessionState = stateOf(sessionId ?: "")

    /** True once the reply to the last [send] has arrived. */
    fun replyIsIn(state: SessionState): Boolean = replyIsIn(state, turnsAtPrompt)

    /** True once the projection carries a turn newer than the last [send]. */
    fun hasNewTurn(state: SessionState): Boolean = hasNewTurn(state, turnsAtPrompt)

    /**
     * The conversation so far, oldest first.
     */
    fun turns(): List<Turn> = state().turns

    /**
     * The conversation with each answer at full length.
     *
     * The list projection clips every turn to about a hundred characters, so a
     * reply that took the agent a page to write arrives as its first line and
     * an ellipsis. This reads the log instead and falls back to the preview
     * only when the log cannot be read or does not reach back far enough.
     */
    fun messages(maxMessages: Int = MESSAGE_PAGE_SIZE): List<ChatMessage> {
        val state = state()
        if (!state.exists) return emptyList()
        val page = pageOf(state, maxMessages)
        return transcript(
            turns = state.turns,
            prompts = fullPromptsOf(page),
            responses = fullResponsesOf(page),
        )
    }

    private fun pageOf(state: SessionState, maxMessages: Int): JSONObject? = try {
        client.pageMessages(state.sessionId, state.asOfSeq, maxMessages)
    } catch (error: Exception) {
        // A chat that can only show previews is still a chat. Losing the whole
        // conversation because one log read failed would be worse.
        null
    }

    private fun stateOf(id: String): SessionState = stateOf(client.listSessions(), id)

    companion object {
        /** Messages pulled back from the log when a reply is rendered in full. */
        const val MESSAGE_PAGE_SIZE = 120

        /** The projection's normalisation: whitespace runs collapse to one space. */
        private val WHITESPACE_RUN = Regex("\\s+")

        /**
         * Builds the transcript from the projected turns plus whatever the log
         * could add.
         *
         * The projection clips prompts as well as replies, and the only full
         * prompt text lives in the log. That log carries no turn number on a
         * user message, so prompts can only be paired by position - and position
         * is sound only when the two lists really do run together. Equal lengths
         * are not proof: a turn the agent started on its own (a background-job
         * notice, which never enters the prompt list) beside a turn that queued
         * a second prompt (a STOP_INSTRUCTION on top of the original) leaves both
         * lists the same size with everything after the first shifted. Pairing
         * then shows one message's words above another's answer and silently
         * drops the rest.
         *
         * So every positional pair has to prove itself against the turn's own
         * preview, and a list that fails anywhere is not used at all: each turn
         * then shows its own clipped preview, which is at least the right turn's
         * text.
         */
        fun transcript(
            turns: List<Turn>,
            prompts: List<String>,
            responses: Map<Int, String>,
        ): List<ChatMessage> {
            val paired = prompts.takeIf { promptsMatchTurns(turns, it) }
            return buildList {
                turns.forEachIndexed { index, turn ->
                    val prompt = paired?.get(index) ?: turn.prompt
                    if (prompt.isNotBlank()) add(ChatMessage(fromUser = true, text = prompt))
                    // Only answered turns get a reply line, so an in-flight turn
                    // does not show half a step's narration as if it were the
                    // answer.
                    if (!turn.isAnswered) return@forEachIndexed
                    val text = responses[index + 1] ?: turn.response
                    if (text.isNotBlank()) add(ChatMessage(fromUser = false, text = text))
                }
            }
        }

        /**
         * True when the log's prompts line up, in order, with the projected
         * turns; see [transcript] for why a matching count is not enough.
         */
        private fun promptsMatchTurns(turns: List<Turn>, prompts: List<String>): Boolean =
            prompts.size == turns.size && turns.indices.all { previewAgrees(prompts[it], turns[it].prompt) }

        /**
         * True when [preview] is the projection's own clipping of [full].
         *
         * The projection space-joins a turn's text blocks and collapses every
         * whitespace run to one space before clipping at a line's worth of
         * characters with a trailing ellipsis, so the two are only comparable
         * after the same normalisation and with the marker removed.
         */
        private fun previewAgrees(full: String, preview: String): Boolean {
            val clipped = collapse(preview).removeSuffix("…").trimEnd()
            return clipped.isNotEmpty() && collapse(full).startsWith(clipped)
        }

        private fun collapse(value: String): String = WHITESPACE_RUN.replace(value, " ").trim()

        /**
         * True once the reply to a prompt is in.
         *
         * Deliberately not just "the newest turn has a response". For a moment
         * after a prompt is accepted the projection still shows the previous
         * turn - which *is* answered - so that test reports a brand-new turn as
         * already finished. The caller then clears its busy state and stops
         * polling before any work has begun: the reply never appears, the
         * spinner and the Stop button vanish, and nothing says why.
         *
         * @param turnsAtPrompt turn count captured before the prompt was sent,
         *   or -1 when unknown (after a reconnect, say), in which case the
         *   looser test is the best available.
         */
        fun replyIsIn(state: SessionState, turnsAtPrompt: Int): Boolean =
            state.exists && state.answered && hasNewTurn(state, turnsAtPrompt)

        /** True once the projection carries a turn newer than the prompt. */
        fun hasNewTurn(state: SessionState, turnsAtPrompt: Int): Boolean =
            turnsAtPrompt < 0 || state.turns.size > turnsAtPrompt

        /**
         * The closing text of each turn, keyed by turn number.
         *
         * Two filters, both load-bearing:
         *
         *  - Only `text` blocks count. An assistant message also carries
         *    `reasoning` blocks, which are the agent's private working, and
         *    concatenating everything would put its thinking in the chat.
         *  - Later messages for a turn replace earlier ones, so what surfaces is
         *    the turn's final answer rather than its intermediate narration.
         */
        fun fullResponsesOf(page: JSONObject?): Map<Int, String> {
            val records = page?.optJSONArray("records") ?: return emptyMap()
            val byTurn = HashMap<Int, String>()
            for (index in 0 until records.length()) {
                val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
                if (event.optString("type") != "assistant/message") continue
                val data = event.optJSONObject("data") ?: continue
                val text = textOf(data.optJSONObject("message")?.optJSONArray("content"))
                if (text.isNotBlank()) byTurn[data.optInt("turn")] = text
            }
            return byTurn
        }

        /**
         * The user's own messages, in order, as the log holds them.
         *
         * A prompt is stored under `data.content`, not `data.message.content`
         * like an assistant reply, and carries no turn number - hence position
         * rather than a key.
         */
        fun fullPromptsOf(page: JSONObject?): List<String> {
            val records = page?.optJSONArray("records") ?: return emptyList()
            val prompts = ArrayList<String>()
            for (index in 0 until records.length()) {
                val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
                if (event.optString("type") != "user/message") continue
                val data = event.optJSONObject("data") ?: continue
                // The harness also writes plugin-sourced messages into the log;
                // those are not the conversation and must not be paired with a
                // turn.
                if (data.optJSONObject("source")?.optString("kind") != "user") continue
                val text = textOf(data.optJSONArray("content"))
                if (text.isNotBlank()) prompts += text
            }
            return prompts
        }

        /** Concatenates the `text` blocks of one message, skipping the rest. */
        private fun textOf(content: JSONArray?): String {
            if (content == null) return ""
            val parts = ArrayList<String>(content.length())
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") != "text") continue
                val text = block.optString("text")
                if (text.isNotBlank()) parts += text
            }
            return parts.joinToString("\n\n")
        }

        /**
         * Reads one session out of a `session/list` response.
         *
         * Matches on the item's own `sessionId` field rather than searching the
         * document for the string: ids also appear inside other sessions' turn
         * text, and that search finds the mention rather than the session.
         */
        fun stateOf(list: JSONObject, sessionId: String): SessionState {
            val items = list.optJSONArray("items") ?: return missing(sessionId)
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("sessionId") != sessionId) continue
                return SessionState(
                    sessionId = sessionId,
                    exists = true,
                    running = item.optBoolean("running"),
                    turns = turnsOf(item),
                    asOfSeq = item.optJSONObject("projections")?.optLong("asOfSeq") ?: 0L,
                )
            }
            return missing(sessionId)
        }

        private fun missing(sessionId: String) = SessionState(
            sessionId = sessionId,
            exists = false,
            running = false,
            turns = emptyList(),
        )

        private fun turnsOf(item: JSONObject): List<Turn> {
            val outline = item
                .optJSONObject("projections")
                ?.optJSONObject("values")
                ?.optJSONArray("turnOutline")
                ?: return emptyList()
            val result = ArrayList<Turn>(outline.length())
            for (index in 0 until outline.length()) {
                val turn = outline.optJSONObject(index) ?: continue
                result += Turn(
                    prompt = turn.optString("prompt"),
                    response = turn.optString("response"),
                    sequence = turn.optLong("seq"),
                )
            }
            return result
        }

        /**
         * Sent immediately after a cancel; see [stop].
         *
         * Deliberately short and final. A longer explanation invites the agent
         * to investigate, and investigating *is* continuing.
         */
        const val STOP_INSTRUCTION =
            "Stop. Do not continue the previous task and do not start a new one. " +
                "Kill any background jobs you started. " +
                "Reply with one short line confirming you have stopped."

        /**
         * Builds the message list a chat window shows from projected turns.
         *
         * Both sides are surfaced even when truncated, because a clipped answer
         * is still an answer; hiding it behind a "…" with no text at all would
         * misrepresent a finished turn as an empty one.
         */
        fun toMessages(turns: List<Turn>): List<ChatMessage> = buildList {
            for (turn in turns) {
                if (turn.prompt.isNotBlank()) add(ChatMessage(fromUser = true, text = turn.prompt))
                if (turn.isAnswered) add(ChatMessage(fromUser = false, text = turn.response))
            }
        }
    }

    /** One exchange as the harness projects it. */
    data class Turn(
        val prompt: String,
        val response: String,
        val sequence: Long,
    ) {
        val isAnswered: Boolean get() = response.isNotBlank()
    }
}

/** One line of the conversation, as the UI shows it. */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
)

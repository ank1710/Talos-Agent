package com.talos.harness.llm

import kotlinx.coroutines.flow.Flow

/**
 * Where completions come from.
 *
 * The agent loop does not care whether the model is in the cloud, on a desktop
 * gateway, or on this phone: it sends a wire request and consumes a stream of
 * [StreamEvent]s. Extracting that contract is what lets an on-device runtime be
 * added without touching [com.talos.harness.agent.AgentLoop].
 *
 * Implementations must honour two things or the loop breaks:
 * - Emit [StreamEvent.Finished] exactly once, including on an empty completion.
 *   The loop treats "no tool calls" as the end of the turn, so a stream that just
 *   stops would hang the agent.
 * - Never throw for an ordinary failure (network, bad key, missing model). Emit
 *   [StreamEvent.Failed] instead, so the UI can show the reason rather than
 *   losing the turn.
 */
interface ChatBackend {

    /**
     * Streams one completion.
     *
     * [apiKey] and [baseUrl] are meaningful only to remote backends; an on-device
     * implementation ignores them. They stay in the signature because the loop is
     * deliberately unaware of which kind of backend it is talking to.
     */
    fun streamChat(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): Flow<StreamEvent>
}

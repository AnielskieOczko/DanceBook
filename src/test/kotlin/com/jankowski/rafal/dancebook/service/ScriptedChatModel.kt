package com.jankowski.rafal.dancebook.service

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [ChatModel] that plays back a script: call N returns script[N], and the last entry repeats.
 * [prompts] records every prompt it was given, so a test can assert what the model was sent.
 */
class ScriptedChatModel(private val script: List<() -> ChatResponse>) : ChatModel {

    val prompts = CopyOnWriteArrayList<Prompt>()

    override fun call(prompt: Prompt): ChatResponse {
        val index = prompts.size
        prompts.add(prompt)
        return script[minOf(index, script.lastIndex)]()
    }

    companion object {
        fun text(text: String): () -> ChatResponse = {
            ChatResponse.builder()
                .generations(listOf(Generation(AssistantMessage.builder().content(text).build())))
                .build()
        }

        fun toolCall(name: String, arguments: String, id: String = "call-1"): () -> ChatResponse = {
            ChatResponse.builder()
                .generations(listOf(Generation(
                    AssistantMessage.builder()
                        .content("")
                        .toolCalls(listOf(AssistantMessage.ToolCall(id, "function", name, arguments)))
                        .build()
                )))
                .build()
        }

        fun failing(message: String): () -> ChatResponse = { throw IllegalStateException(message) }
    }
}

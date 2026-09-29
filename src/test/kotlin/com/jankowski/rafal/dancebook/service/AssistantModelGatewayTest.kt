package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import java.time.Duration

class AssistantModelGatewayTest {

    private val prompt = Prompt(listOf(UserMessage("hi")))

    private fun reply(text: String) = ChatResponse.builder()
        .generations(listOf(Generation(AssistantMessage.builder().content(text).build()))).build()

    @Test
    fun `returns the model's reply`() {
        val model = object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = reply("hello")
        }
        val response = AssistantModelGateway(model).call(prompt, Duration.ofSeconds(5))
        assertEquals("hello", response.result.output.text)
    }

    @Test
    fun `a slow model becomes AssistantUnavailableException`() {
        val model = object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse {
                Thread.sleep(2_000)
                return reply("too late")
            }
        }
        assertThrows(AssistantUnavailableException::class.java) {
            AssistantModelGateway(model).call(prompt, Duration.ofMillis(100))
        }
    }

    @Test
    fun `a model that throws becomes AssistantUnavailableException`() {
        val model = object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = throw IllegalStateException("429")
        }
        assertThrows(AssistantUnavailableException::class.java) {
            AssistantModelGateway(model).call(prompt, Duration.ofSeconds(5))
        }
    }
}

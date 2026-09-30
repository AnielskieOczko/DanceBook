package com.jankowski.rafal.dancebook.config

import com.google.genai.Client
import com.jankowski.rafal.dancebook.service.AssistantModelGateway
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.retry.RetryUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The assistant's model, behind Spring AI's [ChatModel]. To add another provider or a
 * per-request picker later, register another [ChatModel] here; nothing else names Gemini.
 */
@Configuration
class AssistantConfig {

    @Bean
    @ConditionalOnAssistant
    fun assistantToolCallingManager(): ToolCallingManager = ToolCallingManager.builder().build()

    @Bean
    @ConditionalOnAssistant
    fun assistantChatModel(googleAi: GoogleAiProperties, toolCallingManager: ToolCallingManager): ChatModel {
        val client = Client.builder().apiKey(googleAi.apiKey).build()
        val options = GoogleGenAiChatOptions.builder()
            .model(googleAi.assistantModel)
            .temperature(0.2)
            .build()
        return GoogleGenAiChatModel.builder()
            .genAiClient(client)
            .defaultOptions(options)
            .toolCallingManager(toolCallingManager)
            .retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE)
            .build()
    }

    @Bean
    @ConditionalOnAssistant
    fun assistantModelGateway(chatModel: ChatModel): AssistantModelGateway = AssistantModelGateway(chatModel)
}

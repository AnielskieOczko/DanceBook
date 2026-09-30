package com.jankowski.rafal.dancebook.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class AssistantConfigTest {

    private fun runner(apiKey: String) = ApplicationContextRunner()
        .withUserConfiguration(AssistantConfig::class.java)
        .withPropertyValues("google.ai.api-key=$apiKey")
        .withBean(GoogleAiProperties::class.java, Supplier { GoogleAiProperties(apiKey = apiKey) })

    @Test
    fun `a configured key gives a chat model and a tool calling manager`() {
        runner("test-key").run { ctx ->
            assertThat(ctx).hasSingleBean(ChatModel::class.java)
            assertThat(ctx).hasSingleBean(ToolCallingManager::class.java)
        }
    }

    @Test
    fun `no key means no chat model at all`() {
        runner("").run { ctx ->
            assertThat(ctx).doesNotHaveBean(ChatModel::class.java)
            assertThat(ctx).doesNotHaveBean(ToolCallingManager::class.java)
        }
    }

    @Test
    fun `a blank key counts as no key`() {
        runner("   ").run { ctx -> assertThat(ctx).doesNotHaveBean(ChatModel::class.java) }
    }

    @Test
    fun `the feature flag follows the key`() {
        assertThat(AssistantFeature(GoogleAiProperties(apiKey = "k")).enabled).isTrue()
        assertThat(AssistantFeature(GoogleAiProperties(apiKey = "")).enabled).isFalse()
        assertThat(AssistantFeature(GoogleAiProperties(apiKey = " ")).enabled).isFalse()
    }
}

package com.jankowski.rafal.dancebook.service

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The provider timed out or failed. The message is for the log; users get a fixed sentence. */
class AssistantUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One model call with a deadline. Only the network call runs on the worker thread; tools run
 * on the request thread, where the security context and the open-in-view session live.
 */
class AssistantModelGateway(private val chatModel: ChatModel) {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun call(prompt: Prompt, timeout: Duration): ChatResponse {
        val future = CompletableFuture.supplyAsync({ chatModel.call(prompt) }, executor)
        try {
            return future.get(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw AssistantUnavailableException("The model did not answer within $timeout", e)
        } catch (e: ExecutionException) {
            throw AssistantUnavailableException("The model call failed: ${e.cause?.message}", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssistantUnavailableException("Interrupted while waiting for the model", e)
        }
    }
}

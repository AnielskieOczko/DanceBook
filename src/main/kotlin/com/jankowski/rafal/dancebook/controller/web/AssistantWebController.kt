package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.service.AssistantChips
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.DraftNotPendingException
import com.jankowski.rafal.dancebook.service.AssistantConversationService
import com.jankowski.rafal.dancebook.service.AssistantService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The assistant's HTMX endpoints. They exist only when a chat model is configured, so without
 * one every path here is a 404. Each returns a fragment; there is no full-page assistant.
 * A conversation that is not the current user's is a 404 (the services throw
 * `EntityNotFoundException`, which `GlobalNotFoundExceptionHandler` maps).
 */
@Controller
@ConditionalOnAssistant
@RequestMapping("/assistant")
class AssistantWebController(
    private val assistantService: AssistantService,
    private val conversationService: AssistantConversationService,
    private val draftService: AssistantDraftService
) {

    @GetMapping("/start")
    fun start(
        @RequestParam(defaultValue = "OTHER") pageType: PageContextType,
        model: Model
    ): String {
        model.addAttribute("chips", AssistantChips.forPage(pageType))
        return "assistant/fragments :: start"
    }

    @PostMapping("/messages")
    fun send(
        @RequestParam(required = false) conversationId: UUID?,
        @RequestParam text: String,
        @RequestParam(defaultValue = "OTHER") pageType: PageContextType,
        @RequestParam(required = false) pageId: UUID?,
        response: HttpServletResponse,
        model: Model
    ): String {
        if (text.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Say something first")
        val turn = assistantService.send(conversationId, text, PageContext(pageType, pageId))
        if (turn.persisted) response.setHeader("HX-Trigger", "assistant-sent")
        model.addAttribute("turn", turn)
        return "assistant/fragments :: turn"
    }

    @GetMapping("/conversations")
    fun history(@RequestParam(defaultValue = "OTHER") pageType: PageContextType, model: Model): String =
        historyFragment(pageType, model, null)

    @GetMapping("/conversations/{id}")
    fun open(@PathVariable id: UUID, model: Model): String {
        model.addAttribute("view", assistantService.conversation(id))
        return "assistant/fragments :: conversation"
    }

    @PostMapping("/conversations/{id}/rename")
    fun rename(
        @PathVariable id: UUID,
        @RequestParam title: String,
        @RequestParam(defaultValue = "OTHER") pageType: PageContextType,
        model: Model
    ): String {
        val error = try {
            conversationService.rename(id, title)
            null
        } catch (e: IllegalArgumentException) {
            "A conversation needs a name."
        }
        return historyFragment(pageType, model, error)
    }

    @GetMapping("/conversations/{id}/delete-dialog")
    fun deleteDialog(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "OTHER") pageType: PageContextType,
        model: Model
    ): String {
        val conversation = conversationService.findOwned(id)
        val count = conversationService.messages(id).size
        val messagePhrase = if (count == 1) "Its 1 message is removed." else "Its $count messages are removed."
        model.addAttribute("dialogTitle", "Delete conversation")
        model.addAttribute(
            "dialogMessage",
            "Delete “${conversation.title}”? $messagePhrase Notes, training events and figures you saved from it stay."
        )
        model.addAttribute("confirmLabel", "Delete conversation")
        model.addAttribute("cancelLabel", "Keep it")
        model.addAttribute("confirmUrl", "/assistant/conversations/$id/delete?pageType=$pageType")
        model.addAttribute("hxTarget", "#assistantThread")
        model.addAttribute("hxSwap", "innerHTML")
        model.addAttribute("hxInclude", "#assistantConversationId")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @PostMapping("/conversations/{id}/delete")
    fun delete(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "OTHER") pageType: PageContextType,
        @RequestParam(required = false) conversationId: UUID?,
        model: Model
    ): String {
        conversationService.delete(id)
        // The composer still holds the id of the conversation it was talking to; if that is the
        // one just deleted, the next send would be a 404, so the fragment tells the page to let go of it.
        model.addAttribute("resetConversationId", conversationId == id)
        return historyFragment(pageType, model, null)
    }

    /**
     * Save. Answers 200 with the card in every case that has something to show: saved, or
     * still pending with the error in it, because htmx does not swap a 4xx and the user would
     * see nothing. A draft that was already handled is a 409 to anything that is not htmx.
     * Someone else's draft is a 404, as everywhere else.
     */
    @PostMapping("/drafts/{id}/save")
    fun saveDraft(
        @PathVariable id: UUID,
        @RequestHeader("HX-Request", required = false) htmx: Boolean?,
        response: HttpServletResponse,
        model: Model
    ): String {
        val card = try {
            draftService.save(id)
        } catch (e: DraftNotPendingException) {
            if (htmx != true) response.status = HttpStatus.CONFLICT.value()
            draftService.view(id)
        }
        model.addAttribute("draft", card)
        return "assistant/fragments :: draftCard"
    }

    /** Edit in form: the draft is discarded and the browser goes to the create form, prefilled from it. */
    @PostMapping("/drafts/{id}/edit")
    fun editDraft(
        @PathVariable id: UUID,
        @RequestHeader("HX-Request", required = false) htmx: Boolean?
    ): org.springframework.http.ResponseEntity<Void> {
        val url = draftService.openInForm(id)
        val builder = if (htmx == true) {
            org.springframework.http.ResponseEntity.ok().header("HX-Redirect", url)
        } else {
            org.springframework.http.ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", url)
        }
        return builder.build()
    }

    private fun historyFragment(pageType: PageContextType, model: Model, error: String?): String {
        model.addAttribute("conversations", conversationService.list())
        model.addAttribute("pageType", pageType.name)
        model.addAttribute("historyError", error)
        return "assistant/fragments :: history"
    }
}

package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.TrainingHistoryService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * The training history: confirmed sessions, including the ones whose calendar entry is gone.
 *
 * Its own controller, like the statistics and timeline pages, rather than more weight on
 * TrainingEventWebController. The route sits under /training-events so the whole family of
 * training URLs stays together, and NavbarAdvice.activeNav() therefore already highlights
 * Training for it — no branch of its own, unlike the timeline, which is a top-level nav entry.
 */
@Controller
@RequestMapping("/training-events/history")
class TrainingHistoryWebController(
    private val trainingHistoryService: TrainingHistoryService
) {

    @GetMapping
    fun showHistory(model: Model): String {
        model.addAttribute("pageTitle", "Training history")
        model.addAttribute("history", trainingHistoryService.historyForCurrentUser())
        return "training-events/history"
    }

    /**
     * The one correction this page allows: dropping a record whose session has been deleted.
     * A record that still has its session is corrected by re-marking the session instead, so
     * the service rejects it rather than this controller having to know the rule.
     */
    @PostMapping("/{recordId}/delete")
    fun deleteRecord(@PathVariable recordId: UUID): String {
        trainingHistoryService.deleteOrphanedRecord(recordId)
        return "redirect:/training-events/history"
    }
}

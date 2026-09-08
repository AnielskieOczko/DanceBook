package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.TrainingTimelineService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The chronological training timeline.
 *
 * Its own controller, like the statistics page, rather than more weight on
 * TrainingEventWebController.
 *
 * The route sits under /training-events so the calendar, agenda, stats and timeline stay one
 * family of URLs, but NavbarAdvice.activeNav() gives it its own value: it is a top-level entry
 * in the desktop navbar and must not light up the Training link instead of its own.
 */
@Controller
@RequestMapping("/training-events/timeline")
class TrainingTimelineWebController(
    private val trainingTimelineService: TrainingTimelineService
) {

    @GetMapping
    fun showTimeline(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) lastMonth: String? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        // A negative page would page backwards off the end of the history.
        val timeline = trainingTimelineService.timelineForCurrentUser(page.coerceAtLeast(0))

        model.addAttribute("timeline", timeline)
        // The month already on screen when this window was requested. The first heading is
        // suppressed when it matches, so an appended window continues a month rather than
        // repeating its heading halfway down the page.
        model.addAttribute("continuedMonth", lastMonth)

        if (isHtmxRequest != true) {
            model.addAttribute("pageTitle", "Training timeline")
        }

        return if (isHtmxRequest == true) "training-events/timeline :: timelinePage" else "training-events/timeline"
    }
}

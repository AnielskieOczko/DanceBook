package com.jankowski.rafal.dancebook.controller.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.controller.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The training statistics dashboard.
 *
 * Its own controller rather than another responsibility on TrainingEventWebController,
 * which is already 350 lines of create, edit, calendar and series handling.
 *
 * NavbarAdvice.activeNav() matches on the /training-events prefix, so the navbar
 * highlights correctly without a branch of its own.
 */
@Controller
@RequestMapping("/training-events/stats")
class TrainingStatsWebController(
    private val trainingStatsService: TrainingStatsService,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    fun showStats(
        @RequestParam(defaultValue = "ALL_TIME") period: StatsPeriod,
        model: Model
    ): String {
        val stats = trainingStatsService.statsForCurrentUser(period)

        model.addAttribute("pageTitle", "Training stats")
        model.addAttribute("stats", stats)
        model.addAttribute("periods", StatsPeriod.entries.toTypedArray())
        // Serialised here so the template carries markup and nothing else.
        model.addAttribute("categoryChartJson", objectMapper.writeValueAsString(stats.byCategory))
        model.addAttribute("eventTypeChartJson", objectMapper.writeValueAsString(stats.byEventType))
        model.addAttribute("chartGridColor", TrainingEventPalette.CHART_GRID_COLOR)

        return "training-events/stats"
    }
}

package com.jankowski.rafal.dancebook.controller.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantPreferencesService
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
    private val activeCalendarService: ActiveCalendarService,
    private val objectMapper: ObjectMapper,
    private val appUserService: AppUserService,
    private val assistantPreferencesService: AssistantPreferencesService? = null
) {

    @GetMapping
    fun showStats(
        @RequestParam(defaultValue = "ALL_TIME") period: StatsPeriod,
        model: Model
    ): String {
        val user = try { appUserService.getCurrentUser() } catch (e: Exception) { null }
        val stats = trainingStatsService.statsForCurrentUser(period, activeCalendarService.active()?.id)
        val showStreaks = user?.let { assistantPreferencesService?.forUser(it)?.showStreaksAndBadges } ?: true

        model.addAttribute("pageTitle", "Training stats")
        model.addAttribute("stats", stats)
        model.addAttribute("periods", StatsPeriod.entries.toTypedArray())
        // Serialised here so the template carries markup and nothing else.
        model.addAttribute("categoryChartJson", objectMapper.writeValueAsString(stats.byCategory))
        model.addAttribute("eventTypeChartJson", objectMapper.writeValueAsString(stats.byEventType))
        model.addAttribute("chartGridColor", TrainingEventPalette.CHART_GRID_COLOR)
        model.addAttribute("showStreaksAndBadges", showStreaks)

        return "training-events/stats"
    }
}

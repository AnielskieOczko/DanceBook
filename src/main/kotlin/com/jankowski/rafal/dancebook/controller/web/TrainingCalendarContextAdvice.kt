package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Supplies the active-calendar context to the training pages.
 *
 * Deliberately not part of NavbarAdvice, which feeds every page in the app: calendars are
 * irrelevant to the syllabus, materials and choreography pages, and loading them there would
 * be a wasted query on every request.
 */
@ControllerAdvice(
    assignableTypes = [
        TrainingEventWebController::class,
        TrainingStatsWebController::class,
        TrainingTimelineWebController::class,
        TrainingHistoryWebController::class
    ]
)
class TrainingCalendarContextAdvice(
    private val activeCalendarService: ActiveCalendarService
) {

    @ModelAttribute("activeCalendar")
    fun activeCalendar(): TrainingCalendar? = activeCalendarService.active()

    @ModelAttribute("selectableCalendars")
    fun selectableCalendars(): List<TrainingCalendar> = activeCalendarService.selectable()
}

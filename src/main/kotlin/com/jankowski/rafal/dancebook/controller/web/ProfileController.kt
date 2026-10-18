package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.AppLocales
import com.jankowski.rafal.dancebook.dto.FormSelectOption
import com.jankowski.rafal.dancebook.dto.PasswordChangeRequest
import com.jankowski.rafal.dancebook.dto.ProfileActivityItem
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.EventType
import com.jankowski.rafal.dancebook.model.MedalBadgeStyle
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.ChoreographyService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import jakarta.persistence.EntityNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.dto.AssistantPreferences
import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.model.AssistantReplyLanguage
import com.jankowski.rafal.dancebook.model.UserAssistantPreferences
import com.jankowski.rafal.dancebook.service.AssistantPreferencesService
import com.jankowski.rafal.dancebook.service.BadgeProfileSummary
import com.jankowski.rafal.dancebook.service.BadgeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Controller
@RequestMapping("/profile")
@PreAuthorize("isAuthenticated()")
class ProfileController @Autowired constructor(
    private val appUserService: AppUserService,
    private val messageSource: MessageSource,
    private val trainingStatsService: TrainingStatsService,
    private val trainingCalendarService: TrainingCalendarService,
    private val materialService: MaterialService,
    private val choreographyService: ChoreographyService,
    private val danceFigureService: DanceFigureService,
    private val activityEventService: ActivityEventService,
    private val assistantPreferencesService: AssistantPreferencesService? = null,
    private val assistantFeature: AssistantFeature? = null,
    private val badgeService: BadgeService? = null
) {
    @GetMapping
    fun showProfile(model: Model): String {
        val user = appUserService.getCurrentUser()
        val locale = LocaleContextHolder.getLocale()
        val badgeStyleOptions = MedalBadgeStyle.entries.map {
            FormSelectOption(
                value = it.name,
                label = messageSource.getMessage("figures.medal.style.${it.code}", null, it.displayName, locale) ?: it.displayName
            )
        }
        val trainingStats = trainingStatsService.statsForCurrentUser(StatsPeriod.THIS_YEAR)
        val notesCount = materialService.countByOwner(user)
        val choreographiesCount = choreographyService.countByOwner(user)
        val figuresCount = danceFigureService.countByCreatedBy(user)
        val calendars = trainingCalendarService.findAllVisibleTo(user)
        val defaultCalendar = user.defaultCalendar ?: trainingCalendarService.findDefault(user)
        val recentEvents = activityEventService.getRecentEventsByActor(user, 5).map { toProfileActivityItem(it, locale) }
        val memberSince = user.createdAt.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))

        model.addAttribute("appUser", user)
        model.addAttribute("passwordChangeRequest", PasswordChangeRequest())
        model.addAttribute("languageOptions", AppLocales.options())
        model.addAttribute("badgeStyleOptions", badgeStyleOptions)
        model.addAttribute("trainingStats", trainingStats)
        model.addAttribute("notesCount", notesCount)
        model.addAttribute("choreographiesCount", choreographiesCount)
        model.addAttribute("figuresCount", figuresCount)
        model.addAttribute("calendars", calendars)
        model.addAttribute("defaultCalendar", defaultCalendar)
        model.addAttribute("recentEvents", recentEvents)
        model.addAttribute("memberSince", memberSince)
        model.addAttribute("assistantPreferences", assistantPreferencesService?.rawForUser(user) ?: UserAssistantPreferences().apply { userId = user.id })
        model.addAttribute("assistantFeatureEnabled", assistantFeature?.enabled ?: true)
        model.addAttribute("replyLanguageOptions", replyLanguageOptions(locale))
        val showStreaks = assistantPreferencesService?.forUser(user)?.showStreaksAndBadges ?: true
        model.addAttribute("showStreaksAndBadges", showStreaks)
        val badgeSummary = if (showStreaks) {
            badgeService?.profileSummaryForUser(user, locale) ?: BadgeProfileSummary.empty()
        } else {
            BadgeProfileSummary.empty()
        }
        model.addAttribute("badgeSummary", badgeSummary)
        return "profile/index"
    }

    @PostMapping("/streaks-and-badges")
    fun updateStreaksAndBadges(
        @RequestParam(required = false) showStreaksAndBadges: String?,
        @RequestParam(name = "_showStreaksAndBadges", required = false) hasMarker: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = appUserService.getCurrentUser()

        val effectiveValue = when {
            hasMarker != null -> when (showStreaksAndBadges) {
                null, "false", "off" -> false
                "true", "on" -> true
                else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown value for showStreaksAndBadges: $showStreaksAndBadges")
            }
            showStreaksAndBadges != null -> when (showStreaksAndBadges.trim().lowercase()) {
                "true", "on", "1" -> true
                "false", "off", "0" -> false
                else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown value for showStreaksAndBadges: $showStreaksAndBadges")
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing value for showStreaksAndBadges")
        }

        var savedValue: Boolean
        try {
            val updated = assistantPreferencesService?.updatePreferences(
                user,
                AssistantPreferencesUpdateRequest(showStreaksAndBadges = effectiveValue)
            ) ?: AssistantPreferences.DEFAULTS.copy(showStreaksAndBadges = effectiveValue)
            savedValue = updated.showStreaksAndBadges
            model.addAttribute("showStreaksAndBadgesSuccessMsg", true)
            redirectAttributes.addFlashAttribute("showStreaksAndBadgesSuccessMsg", true)
        } catch (e: Exception) {
            val current = assistantPreferencesService?.forUser(user) ?: AssistantPreferences.DEFAULTS
            savedValue = current.showStreaksAndBadges
            model.addAttribute("showStreaksAndBadgesErrorMsg", true)
            redirectAttributes.addFlashAttribute("showStreaksAndBadgesErrorMsg", true)
        }

        model.addAttribute("showStreaksAndBadges", savedValue)
        model.addAttribute("streaksAndBadgesOpen", true)

        if (request.getHeader("HX-Request") != null) {
            return "profile/index :: streaksAndBadgesSection"
        }
        return "redirect:/profile"
    }

    @PostMapping("/default-calendar")
    fun updateDefaultCalendar(
        @RequestParam id: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = appUserService.getCurrentUser()
        val locale = LocaleContextHolder.getLocale()
        try {
            val calendar = trainingCalendarService.findByIdVisibleTo(id, user)
                ?: throw EntityNotFoundException("Training calendar with id $id not found")
            trainingCalendarService.setDefault(id, user)
            val successMsg = messageSource.getMessage(
                "profile.settings.calendars.success",
                arrayOf(calendar.displayName),
                "\"${calendar.displayName}\" is now your default calendar.",
                locale
            )
            redirectAttributes.addFlashAttribute("calendarSuccess", successMsg)
        } catch (e: Exception) {
            val errorMsg = messageSource.getMessage(
                "profile.settings.calendars.error",
                null,
                e.message ?: "Failed to set default calendar",
                locale
            )
            redirectAttributes.addFlashAttribute("calendarError", errorMsg)
        }
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", "/profile")
        }
        return "redirect:/profile"
    }

    @PostMapping("/badge-style", "/medal-badge-style")
    fun updateMedalBadgeStyle(
        @RequestParam(required = false) medalBadgeStyle: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = appUserService.getCurrentUser()
        val trimmed = medalBadgeStyle?.trim()

        if (!trimmed.isNullOrEmpty() && MedalBadgeStyle.fromStringOrNull(trimmed) == null) {
            redirectAttributes.addFlashAttribute("badgeStyleErrorMsg", true)
            if (request.getHeader("HX-Request") != null) {
                response.setHeader("HX-Redirect", "/profile")
            }
            return "redirect:/profile"
        }

        try {
            val style = if (trimmed.isNullOrEmpty()) null else MedalBadgeStyle.fromStringOrNull(trimmed)
            appUserService.updateMedalBadgeStyle(user.id!!, style)
            redirectAttributes.addFlashAttribute("badgeStyleSuccessMsg", true)
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("badgeStyleErrorMsg", true)
        }

        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", "/profile")
        }
        return "redirect:/profile"
    }

    @PostMapping("/locale", "/language")
    fun updateLocale(
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) redirect: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = appUserService.getCurrentUser()
        val trimmed = locale?.trim()

        val target = SafeRedirect.orDefault(redirect, "/profile")

        if (!trimmed.isNullOrEmpty() && !AppLocales.isSupported(trimmed)) {
            redirectAttributes.addFlashAttribute("localeErrorMsg", true)
            if (request.getHeader("HX-Request") != null) {
                response.setHeader("HX-Redirect", target)
            }
            return "redirect:$target"
        }

        try {
            appUserService.updateLocale(user.id!!, trimmed)
            redirectAttributes.addFlashAttribute("localeSuccessMsg", true)
        } catch (e: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute("localeErrorMsg", true)
        }

        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", target)
        }
        return "redirect:$target"
    }

    @PostMapping("/password")
    fun changePassword(
        @Valid @ModelAttribute("passwordChangeRequest") request: PasswordChangeRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        val user = appUserService.getCurrentUser()
        model.addAttribute("appUser", user)
        model.addAttribute("languageOptions", AppLocales.options())

        if (bindingResult.hasErrors()) {
            return "profile/index :: passwordSection"
        }

        try {
            appUserService.changePassword(user.id!!, request)
            val successMsg = messageSource.getMessage(
                "profile.password_updated",
                null,
                "Password successfully updated! It will be used on your next login.",
                LocaleContextHolder.getLocale()
            )
            model.addAttribute("passwordSuccessMsg", successMsg)
            model.addAttribute("passwordChangeRequest", PasswordChangeRequest())
        } catch (e: Exception) {
            bindingResult.reject("passwordError", e.message ?: "Failed to update password")
        }

        return "profile/index :: passwordSection"
    }

    @PostMapping("/assistant-preferences")
    fun updateAssistantPreferences(
        @RequestParam(required = false) assistantEnabled: Boolean?,
        @RequestParam(required = false) semanticSearchEnabled: Boolean?,
        @RequestParam(required = false) relatedNotesEnabled: Boolean?,
        @RequestParam(required = false) suggestFiguresEnabled: Boolean?,
        @RequestParam(required = false) openInFullWindow: Boolean?,
        @RequestParam(required = false) includeSharedItems: Boolean?,
        @RequestParam(required = false) replyLanguage: String?,
        @RequestParam(name = "_assistantEnabled", required = false) hasAssistantMarker: String?,
        @RequestParam(name = "_semanticSearchEnabled", required = false) hasSemanticMarker: String?,
        @RequestParam(name = "_relatedNotesEnabled", required = false) hasRelatedNotesMarker: String?,
        @RequestParam(name = "_suggestFiguresEnabled", required = false) hasSuggestMarker: String?,
        @RequestParam(name = "_openInFullWindow", required = false) hasFullWindowMarker: String?,
        @RequestParam(name = "_includeSharedItems", required = false) hasIncludeSharedMarker: String?,
        request: HttpServletRequest,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = appUserService.getCurrentUser()
        val locale = LocaleContextHolder.getLocale()

        val parsedLang = if (!replyLanguage.isNullOrBlank()) {
            AssistantReplyLanguage.fromStringOrNull(replyLanguage)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown reply language: $replyLanguage")
        } else {
            null
        }

        val effectiveAssistantEnabled = if (hasAssistantMarker != null) assistantEnabled == true else assistantEnabled
        val effectiveSemanticSearch = if (hasSemanticMarker != null) semanticSearchEnabled == true else semanticSearchEnabled
        val effectiveRelatedNotes = if (hasRelatedNotesMarker != null) relatedNotesEnabled == true else relatedNotesEnabled
        val effectiveSuggestFigures = if (hasSuggestMarker != null) suggestFiguresEnabled == true else suggestFiguresEnabled
        val effectiveFullWindow = if (hasFullWindowMarker != null) openInFullWindow == true else openInFullWindow
        val effectiveIncludeShared = if (hasIncludeSharedMarker != null) includeSharedItems == true else includeSharedItems

        val update = AssistantPreferencesUpdateRequest(
            assistantEnabled = effectiveAssistantEnabled,
            semanticSearchEnabled = effectiveSemanticSearch,
            relatedNotesEnabled = effectiveRelatedNotes,
            suggestFiguresEnabled = effectiveSuggestFigures,
            openInFullWindow = effectiveFullWindow,
            includeSharedItems = effectiveIncludeShared,
            replyLanguage = parsedLang
        )

        try {
            val updated = assistantPreferencesService?.updatePreferences(user, update)
                ?: UserAssistantPreferences().apply { userId = user.id }
            model.addAttribute("assistantPreferences", updated)
            model.addAttribute("assistantSuccessMsg", true)
            redirectAttributes.addFlashAttribute("assistantSuccessMsg", true)
        } catch (e: Exception) {
            val current = assistantPreferencesService?.rawForUser(user)
                ?: UserAssistantPreferences().apply { userId = user.id }
            model.addAttribute("assistantPreferences", current)
            model.addAttribute("assistantErrorMsg", true)
            redirectAttributes.addFlashAttribute("assistantErrorMsg", true)
        }

        model.addAttribute("assistantFeatureEnabled", assistantFeature?.enabled ?: true)
        model.addAttribute("replyLanguageOptions", replyLanguageOptions(locale))
        model.addAttribute("assistantOpen", true)

        if (request.getHeader("HX-Request") != null) {
            return "profile/index :: assistantSection"
        }
        return "redirect:/profile"
    }

    private fun replyLanguageOptions(locale: Locale): List<FormSelectOption> =
        AssistantReplyLanguage.entries.map {
            val labelKey = when (it) {
                AssistantReplyLanguage.APP -> "profile.assistant.reply_language.app"
                AssistantReplyLanguage.PL -> "profile.assistant.reply_language.pl"
                AssistantReplyLanguage.EN -> "profile.assistant.reply_language.en"
            }
            FormSelectOption(
                value = it.name,
                label = messageSource.getMessage(labelKey, null, it.name, locale) ?: it.name
            )
        }

    private fun toProfileActivityItem(event: ActivityEvent, locale: Locale): ProfileActivityItem {
        val icon = when (event.eventType) {
            EventType.MATERIAL_CREATED -> "description"
            EventType.MATERIAL_UPDATED -> "edit"
            EventType.MATERIAL_DELETED -> "delete"
            EventType.MATERIAL_VISIBILITY_CHANGED -> "visibility"
            EventType.COMMENT_ADDED -> "chat_bubble"
            EventType.LIST_CREATED -> "layers"
            EventType.LIST_MADE_PUBLIC -> "public"
            EventType.MATERIAL_FIGURE_ADDED -> "playlist_add"
            EventType.MATERIAL_FIGURE_UPDATED -> "playlist_add_check"
            EventType.MATERIAL_FIGURE_DELETED -> "playlist_remove"
            EventType.DANCE_FIGURE_CREATED -> "star"
            EventType.DANCE_FIGURE_UPDATED -> "edit_note"
            EventType.DANCE_FIGURE_DELETED -> "delete"
            EventType.TRAINING_EVENT_CREATED -> "event_available"
            EventType.TRAINING_EVENT_UPDATED -> "edit_calendar"
            EventType.TRAINING_EVENT_DELETED -> "event_busy"
            EventType.TRAINING_SERIES_CREATED -> "event_repeat"
            EventType.TRAINING_SERIES_DELETED -> "event_busy"
            EventType.TRAINING_BULK_ATTENDANCE_UPDATED -> "fact_check"
            EventType.TRAINING_BULK_DELETED -> "event_busy"
            EventType.TRAINING_BULK_UPDATED -> "edit_calendar"
            EventType.BADGE_EARNED -> "military_tech"
        }

        val messageKey = when (event.eventType) {
            EventType.MATERIAL_CREATED -> "profile.activity.material_created"
            EventType.MATERIAL_UPDATED -> "profile.activity.material_updated"
            EventType.MATERIAL_DELETED -> "profile.activity.material_deleted"
            EventType.MATERIAL_VISIBILITY_CHANGED -> "profile.activity.visibility_changed"
            EventType.COMMENT_ADDED -> "profile.activity.comment_added"
            EventType.LIST_CREATED -> "profile.activity.list_created"
            EventType.LIST_MADE_PUBLIC -> "profile.activity.list_shared"
            EventType.MATERIAL_FIGURE_ADDED -> "profile.activity.figure_added"
            EventType.MATERIAL_FIGURE_UPDATED -> "profile.activity.figure_updated"
            EventType.MATERIAL_FIGURE_DELETED -> "profile.activity.figure_deleted"
            EventType.DANCE_FIGURE_CREATED -> "profile.activity.dance_figure_created"
            EventType.DANCE_FIGURE_UPDATED -> "profile.activity.dance_figure_updated"
            EventType.DANCE_FIGURE_DELETED -> "profile.activity.dance_figure_deleted"
            EventType.TRAINING_EVENT_CREATED -> "profile.activity.training_created"
            EventType.TRAINING_EVENT_UPDATED -> "profile.activity.training_updated"
            EventType.TRAINING_EVENT_DELETED -> "profile.activity.training_deleted"
            EventType.TRAINING_SERIES_CREATED -> "profile.activity.training_series_created"
            EventType.TRAINING_SERIES_DELETED -> "profile.activity.training_series_deleted"
            EventType.TRAINING_BULK_ATTENDANCE_UPDATED -> "profile.activity.bulk_attendance"
            EventType.TRAINING_BULK_DELETED -> "profile.activity.bulk_deleted"
            EventType.TRAINING_BULK_UPDATED -> "profile.activity.bulk_updated"
            EventType.BADGE_EARNED -> "profile.activity.badge_earned"
        }

        val actionText = messageSource.getMessage(messageKey, null, event.eventType.name, locale) ?: event.eventType.name
        val resolvedTargetName = if (event.eventType == EventType.BADGE_EARNED) {
            event.badgeType?.let { messageSource.getMessage(it.nameKey, null, it.name, locale) } ?: event.targetName
        } else {
            event.targetName
        }
        val createdAt = event.createdAt
        val dateLabel = if (createdAt.toLocalDate() == LocalDate.now()) {
            messageSource.getMessage("training.today", null, "Today", locale) ?: "Today"
        } else {
            DateTimeFormatter.ofPattern("d MMM", locale).format(createdAt)
        }

        return ProfileActivityItem(
            icon = icon,
            actionText = actionText,
            targetName = resolvedTargetName,
            dateLabel = dateLabel,
            createdAt = createdAt
        )
    }
}

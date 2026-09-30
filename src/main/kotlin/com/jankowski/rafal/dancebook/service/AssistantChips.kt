package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.PageContextType

/** The suggestion chips for the empty conversation, chosen by page. */
object AssistantChips {
    fun forPage(type: PageContextType): List<String> = when (type) {
        PageContextType.NOTE -> listOf("Which figures are pinned to this note?", "Find other notes about the same figures", "What's on this week?")
        PageContextType.FIGURE -> listOf("Which of my notes mention this figure?", "Find figures similar to this one", "What's on this week?")
        PageContextType.SESSION -> listOf("Which notes did I write about this session?", "What else is on this week?", "Which sessions still need confirming?")
        else -> listOf("Find figures with a heel turn", "Which of my notes talk about sway?", "Which sessions still need confirming?")
    }
}

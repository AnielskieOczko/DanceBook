package com.jankowski.rafal.dancebook.model

/**
 * `PENDING` can be saved. `SAVED` was (or is being) saved: a draft is saved at most once.
 * `DISCARDED` was opened in the form instead, so the card is greyed out and cannot be saved.
 */
enum class DraftStatus { PENDING, SAVED, DISCARDED }

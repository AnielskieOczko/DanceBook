package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DraftStatus

/** Raised when a draft that is already saved or discarded is saved again, or a saved one opened in the form. */
class DraftNotPendingException(val status: DraftStatus) :
    RuntimeException("This draft is already ${status.name.lowercase()}")

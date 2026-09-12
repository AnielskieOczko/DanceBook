package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingHistory
import java.util.UUID

/** The signed-in user's confirmed training, and the one correction it allows. */
interface TrainingHistoryService {

    fun historyForCurrentUser(): TrainingHistory

    /**
     * Removes a record whose calendar session no longer exists.
     *
     * Only orphaned records: while the session is still there, the way to correct a mis-mark
     * is to change the session's attendance, which rewrites the record. Deleting the record
     * directly would leave a confirmed session with no history behind it.
     */
    fun deleteOrphanedRecord(recordId: UUID)
}

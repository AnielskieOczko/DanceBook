package com.jankowski.rafal.dancebook.model

/**
 * The two outcomes a confirmed training session can have.
 *
 * Deliberately narrower than [AttendanceStatus]: PLANNED and CANCELLED are states of the
 * *schedule*, and a record only ever exists for a session whose outcome is known. Reusing
 * the wider enum would make "a record of a planned session" representable, and it is not.
 */
enum class TrainingOutcome {
    ATTENDED,
    SKIPPED;

    companion object {
        /** Null for the statuses that are not a confirmed outcome — unconfirmed is unknown. */
        fun from(status: AttendanceStatus): TrainingOutcome? = when (status) {
            AttendanceStatus.ATTENDED -> ATTENDED
            AttendanceStatus.SKIPPED -> SKIPPED
            AttendanceStatus.PLANNED, AttendanceStatus.CANCELLED -> null
        }
    }
}

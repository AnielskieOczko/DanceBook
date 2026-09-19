package com.jankowski.rafal.dancebook.service

/**
 * Reconciles training records that are missing their calendar reference while the
 * session they came from still has one.
 */
interface TrainingRecordReconciliationService {

    /**
     * Repairs candidate records by copying the calendar reference and snapshot display name
     * from their surviving session.
     *
     * @return the number of records repaired
     */
    fun reconcile(): Int
}

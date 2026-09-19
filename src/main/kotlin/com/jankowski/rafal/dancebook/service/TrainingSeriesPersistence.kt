package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.model.TrainingBulkUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingSeriesCreatedEvent
import com.jankowski.rafal.dancebook.model.TrainingSeriesDeletedEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingSeriesRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * The short local writes for a series, kept in their own bean for the same reason as
 * [TrainingEventPersistence]: the generating service must not be @Transactional across
 * Google Calendar I/O, but the writes still need a transaction for
 * @TransactionalEventListener(AFTER_COMMIT) to fire, and a self-invoked @Transactional
 * method would bypass the proxy.
 */
@Component
class TrainingSeriesPersistence(
    private val trainingSeriesRepository: TrainingSeriesRepository,
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingRecordWriter: TrainingRecordWriter,
    private val eventPublisher: ApplicationEventPublisher
) {

    /**
     * Saves the series with all its occurrences in one transaction, publishing a single
     * activity event rather than one per occurrence — otherwise a 26-week series would
     * bury every other entry in the feed.
     */
    @Transactional
    fun insertSeries(series: TrainingSeries, occurrences: List<TrainingEvent>, actor: AppUser): List<TrainingEvent> {
        // No records to write: every generated occurrence starts PLANNED, and an unconfirmed
        // session is not history. They get records when the user confirms them one by one.
        val savedSeries = trainingSeriesRepository.save(series)
        occurrences.forEach { it.series = savedSeries }
        val saved = trainingEventRepository.saveAll(occurrences)

        eventPublisher.publishEvent(TrainingSeriesCreatedEvent(saved.first(), saved.size, actor))
        return saved
    }

    /** Replaces the future occurrences of a series in one transaction. */
    @Transactional
    fun replaceOccurrences(
        series: TrainingSeries,
        removed: List<TrainingEvent>,
        added: List<TrainingEvent>
    ): List<TrainingEvent> {
        trainingSeriesRepository.save(series)
        // "This and following" can cut across sessions the user already confirmed. Their
        // records outlive the regeneration rather than vanishing with the old occurrences.
        trainingRecordWriter.orphan(removed.mapNotNull { it.id })
        trainingEventRepository.deleteAll(removed)
        added.forEach { it.series = series }
        return trainingEventRepository.saveAll(added)
    }

    /**
     * Updates series occurrences in place without re-generating rows or changing event IDs,
     * keeping attendance status and syncing training records.
     */
    @Transactional
    fun updateOccurrencesInPlace(
        series: TrainingSeries,
        occurrences: List<TrainingEvent>,
        actor: AppUser
    ): List<TrainingEvent> {
        trainingSeriesRepository.save(series)
        val saved = trainingEventRepository.saveAll(occurrences)
        saved.forEach { trainingRecordWriter.sync(it) }
        eventPublisher.publishEvent(
            TrainingBulkUpdatedEvent(
                count = saved.size,
                updateType = "repeating series",
                actor = actor
            )
        )
        return saved
    }

    /**
     * Reconciles series occurrences after a recurrence pattern edit:
     * saves surviving occurrences, creates new occurrences, detaches surviving excess occurrences,
     * deletes unrecorded excess occurrences, and syncs training records.
     */
    @Transactional
    fun reconcileSeries(
        series: TrainingSeries,
        survivingOccurrences: List<TrainingEvent>,
        newOccurrences: List<TrainingEvent>,
        detachedOccurrences: List<TrainingEvent>,
        deletedOccurrences: List<TrainingEvent>,
        actor: AppUser
    ): List<TrainingEvent> {
        trainingSeriesRepository.save(series)

        if (detachedOccurrences.isNotEmpty()) {
            detachedOccurrences.forEach { it.series = null }
            trainingEventRepository.saveAll(detachedOccurrences)
        }

        if (deletedOccurrences.isNotEmpty()) {
            deletedOccurrences.forEach { it.series = null }
            trainingRecordWriter.orphan(deletedOccurrences.mapNotNull { it.id })
            trainingEventRepository.deleteAll(deletedOccurrences)
        }

        val savedSurviving = if (survivingOccurrences.isNotEmpty()) {
            val saved = trainingEventRepository.saveAll(survivingOccurrences)
            saved.forEach { trainingRecordWriter.sync(it) }
            saved
        } else emptyList()

        val savedNew = if (newOccurrences.isNotEmpty()) {
            newOccurrences.forEach { it.series = series }
            trainingEventRepository.saveAll(newOccurrences)
        } else emptyList()

        val totalActive = savedSurviving.size + savedNew.size
        eventPublisher.publishEvent(
            TrainingBulkUpdatedEvent(
                count = totalActive,
                updateType = "repeating series",
                actor = actor
            )
        )

        return savedSurviving + savedNew
    }

    /**
     * Detaches a single occurrence from its series, updating it as a standalone session.
     * If this leaves the series with zero occurrences, deletes the series definition.
     */
    @Transactional
    fun detachAndSave(
        event: TrainingEvent,
        series: TrainingSeries,
        actor: AppUser
    ): TrainingEvent {
        event.series = null
        val saved = trainingEventRepository.save(event)
        trainingRecordWriter.sync(saved)
        val remaining = trainingEventRepository.countBySeries(series)
        if (remaining == 0L) {
            trainingSeriesRepository.delete(series)
        }
        eventPublisher.publishEvent(TrainingEventUpdatedEvent(saved, actor))
        return saved
    }

    @Transactional
    fun removeOccurrences(
        occurrences: List<TrainingEvent>,
        series: TrainingSeries? = occurrences.firstOrNull()?.series,
        actor: AppUser? = null
    ) {
        if (occurrences.isEmpty()) return
        val seriesToClean = series ?: occurrences.firstNotNullOfOrNull { it.series }
        val ids = occurrences.mapNotNull { it.id }
        occurrences.forEach { it.series = null }
        trainingRecordWriter.orphan(ids)
        trainingEventRepository.deleteAll(occurrences)
        if (seriesToClean != null) {
            val remaining = trainingEventRepository.countBySeries(seriesToClean)
            val seriesRemoved = remaining == 0L
            if (seriesRemoved) {
                trainingSeriesRepository.delete(seriesToClean)
            }
            if (actor != null) {
                eventPublisher.publishEvent(
                    TrainingSeriesDeletedEvent(
                        seriesTitle = seriesToClean.title,
                        deletedCount = ids.size,
                        seriesRemoved = seriesRemoved,
                        actor = actor
                    )
                )
            }
        }
    }

    @Transactional
    fun deleteAll(
        series: TrainingSeries,
        surviving: List<TrainingEvent>,
        toDelete: List<TrainingEvent>,
        actor: AppUser
    ) {
        // Detach surviving events so they become standalone sessions
        surviving.forEach { it.series = null }
        trainingEventRepository.saveAll(surviving)

        // Remove non-surviving events, orphaning their records if any
        if (toDelete.isNotEmpty()) {
            toDelete.forEach { it.series = null }
            trainingRecordWriter.orphan(toDelete.mapNotNull { it.id })
            trainingEventRepository.deleteAll(toDelete)
        }

        // The series definition no longer has any occurrences referencing it
        trainingSeriesRepository.delete(series)

        eventPublisher.publishEvent(
            TrainingSeriesDeletedEvent(
                seriesTitle = series.title,
                deletedCount = toDelete.size,
                seriesRemoved = true,
                actor = actor
            )
        )
    }
}

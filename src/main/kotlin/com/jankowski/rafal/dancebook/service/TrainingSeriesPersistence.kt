package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
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

package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.model.TrainingSeriesCreatedEvent
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
    private val eventPublisher: ApplicationEventPublisher
) {

    /**
     * Saves the series with all its occurrences in one transaction, publishing a single
     * activity event rather than one per occurrence — otherwise a 26-week series would
     * bury every other entry in the feed.
     */
    @Transactional
    fun insertSeries(series: TrainingSeries, occurrences: List<TrainingEvent>, actor: AppUser): List<TrainingEvent> {
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
        trainingEventRepository.deleteAll(removed)
        added.forEach { it.series = series }
        return trainingEventRepository.saveAll(added)
    }

    @Transactional
    fun removeOccurrences(occurrences: List<TrainingEvent>) {
        trainingEventRepository.deleteAll(occurrences)
    }
}

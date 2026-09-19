package com.jankowski.rafal.dancebook.service

import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Seeds the default training calendar from `google.calendar.calendar-id` on startup,
 * backfills any existing training events that lack a calendar reference, and reconciles
 * any training records missing their calendar copy while their session has one.
 *
 * This is a separate component from [TrainingCalendarServiceImpl] because
 * [TrainingCalendarService.bootstrapDefaultCalendar] performs a `@Modifying` bulk update
 * ([com.jankowski.rafal.dancebook.repository.TrainingEventRepository.assignMissingCalendar]),
 * which requires an active transaction. A self-invoked `@Transactional` method on the same bean
 * bypasses Spring's AOP proxy, so calling it from `run()` on the service itself would execute
 * without a transaction and fail at runtime. Crossing the bean boundary ensures the proxy
 * intercepts the call and opens an ambient transaction.
 *
 * The order of the two calls in [run] is load-bearing and must not be swapped. The backfill is
 * what gives sessions their calendar on a database restored from a snapshot predating #52; the
 * reconciliation then copies that calendar onto their records. Reversed, records would read
 * sessions that have not been given a calendar yet, the restore would silently need a second
 * boot to heal, and #61 would be back. `TrainingCalendarBootstrapTest` holds the order.
 */
@Component
class TrainingCalendarBootstrap(
    private val trainingCalendarService: TrainingCalendarService,
    private val trainingRecordReconciliationService: TrainingRecordReconciliationService
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        trainingCalendarService.bootstrapDefaultCalendar()
        trainingRecordReconciliationService.reconcile()
    }
}

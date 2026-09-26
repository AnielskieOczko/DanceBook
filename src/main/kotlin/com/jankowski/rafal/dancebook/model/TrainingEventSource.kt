package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "training_event_source")
class TrainingEventSource {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_event_id", nullable = false)
    var trainingEvent: TrainingEvent? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_source_id", nullable = false)
    var calendarSource: CalendarSource? = null

    @Column(name = "google_event_id", nullable = false)
    var googleEventId: String = ""

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}

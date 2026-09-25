package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * Attendance status for one user on one training event.
 */
@Entity
@Table(
    name = "attendance",
    uniqueConstraints = [
        UniqueConstraint(name = "unique_attendance_event_user", columnNames = ["training_event_id", "user_id"])
    ]
)
class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_event_id", nullable = false)
    var trainingEvent: TrainingEvent? = null

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUser? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AttendanceStatus = AttendanceStatus.PLANNED

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
}

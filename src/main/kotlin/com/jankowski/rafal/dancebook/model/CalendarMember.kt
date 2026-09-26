package com.jankowski.rafal.dancebook.model

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

enum class CalendarMemberRole {
    OWNER,
    VIEWER
}

enum class CalendarMemberState {
    INVITED,
    ACTIVE
}

@Entity
@Table(name = "calendar_member")
class CalendarMember {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "calendar_id", nullable = false)
    var calendar: TrainingCalendar? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: AppUser? = null

    @Column(name = "invited_email")
    var invitedEmail: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: CalendarMemberRole = CalendarMemberRole.VIEWER

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: CalendarMemberState = CalendarMemberState.ACTIVE

    @Column(name = "created_at", updatable = false, nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
}

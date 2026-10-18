package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

enum class BadgeTier(val cssClass: String) {
    BRONZE("bronze"),
    SILVER("silver"),
    GOLD("gold"),
    INK("ink")
}

enum class BadgeType(
    val tier: BadgeTier,
    val icon: String,
    val medallionLabel: String?,
    val nameKey: String,
    val descKey: String
) {
    STREAK_5(
        tier = BadgeTier.BRONZE,
        icon = "local_fire_department",
        medallionLabel = "5",
        nameKey = "badges.streak_5.name",
        descKey = "badges.streak_5.desc"
    ),
    STREAK_10(
        tier = BadgeTier.SILVER,
        icon = "local_fire_department",
        medallionLabel = "10",
        nameKey = "badges.streak_10.name",
        descKey = "badges.streak_10.desc"
    ),
    STREAK_20(
        tier = BadgeTier.GOLD,
        icon = "local_fire_department",
        medallionLabel = "20",
        nameKey = "badges.streak_20.name",
        descKey = "badges.streak_20.desc"
    ),
    HOURS_50(
        tier = BadgeTier.BRONZE,
        icon = "schedule",
        medallionLabel = "50h",
        nameKey = "badges.hours_50.name",
        descKey = "badges.hours_50.desc"
    ),
    HOURS_100(
        tier = BadgeTier.SILVER,
        icon = "schedule",
        medallionLabel = "100h",
        nameKey = "badges.hours_100.name",
        descKey = "badges.hours_100.desc"
    ),
    HOURS_250(
        tier = BadgeTier.GOLD,
        icon = "schedule",
        medallionLabel = "250h",
        nameKey = "badges.hours_250.name",
        descKey = "badges.hours_250.desc"
    ),
    PERFECT_MONTH(
        tier = BadgeTier.INK,
        icon = "event_available",
        medallionLabel = null,
        nameKey = "badges.perfect_month.name",
        descKey = "badges.perfect_month.desc"
    ),
    STYLE_BALANCE(
        tier = BadgeTier.INK,
        icon = "balance",
        medallionLabel = null,
        nameKey = "badges.style_balance.name",
        descKey = "badges.style_balance.desc"
    );

    val isIconOnly: Boolean
        get() = medallionLabel == null
}

@Entity
@Table(name = "user_badge")
class UserBadge {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUser? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "badge_type", nullable = false, length = 40)
    var badgeType: BadgeType = BadgeType.STREAK_5

    @Column(name = "month_key", nullable = false, length = 7)
    var monthKey: String = ""

    @Column(name = "training_event_id")
    var trainingEventId: UUID? = null

    @Column(name = "earned_at", nullable = false)
    var earnedAt: LocalDateTime = LocalDateTime.now()
}

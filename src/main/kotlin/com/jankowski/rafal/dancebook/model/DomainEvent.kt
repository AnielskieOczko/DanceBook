package com.jankowski.rafal.dancebook.model

import java.util.UUID

/**
 * Type-safe domain events published via Spring's ApplicationEventPublisher.
 * These are consumed by ActivityEventListener to persist activity log entries.
 */
sealed class DomainEvent(val actor: AppUser)

class MaterialCreatedEvent(
    val material: Material,
    actor: AppUser
) : DomainEvent(actor)

class MaterialUpdatedEvent(
    val material: Material,
    actor: AppUser
) : DomainEvent(actor)

class MaterialDeletedEvent(
    val materialId: UUID,
    val materialName: String,
    actor: AppUser
) : DomainEvent(actor)

class CommentAddedEvent(
    val comment: Comment,
    val material: Material,
    actor: AppUser
) : DomainEvent(actor)

class ListCreatedEvent(
    val list: CustomList,
    actor: AppUser
) : DomainEvent(actor)

class ListMadePublicEvent(
    val list: CustomList,
    actor: AppUser
) : DomainEvent(actor)

class MaterialFigureAddedEvent(
    val material: Material,
    val figureName: String,
    actor: AppUser
) : DomainEvent(actor)

class MaterialFigureUpdatedEvent(
    val material: Material,
    val figureName: String,
    actor: AppUser
) : DomainEvent(actor)

class MaterialFigureDeletedEvent(
    val material: Material,
    val figureName: String,
    actor: AppUser
) : DomainEvent(actor)

class DanceFigureCreatedEvent(
    val danceFigure: DanceFigure,
    actor: AppUser
) : DomainEvent(actor)

class DanceFigureUpdatedEvent(
    val danceFigure: DanceFigure,
    actor: AppUser
) : DomainEvent(actor)

class DanceFigureDeletedEvent(
    val danceFigureId: UUID,
    val danceFigureName: String,
    actor: AppUser
) : DomainEvent(actor)


class TrainingEventCreatedEvent(
    val trainingEvent: TrainingEvent,
    actor: AppUser
) : DomainEvent(actor)

class TrainingEventUpdatedEvent(
    val trainingEvent: TrainingEvent,
    actor: AppUser
) : DomainEvent(actor)

class TrainingEventDeletedEvent(
    val trainingEventId: UUID,
    val trainingEventTitle: String,
    actor: AppUser
) : DomainEvent(actor)

/**
 * One activity entry for a whole generated series. Targets the first occurrence rather
 * than the series row so the feed's existing TRAINING_EVENT link handling still applies.
 */
class TrainingSeriesCreatedEvent(
    val firstOccurrence: TrainingEvent,
    val occurrenceCount: Int,
    actor: AppUser
) : DomainEvent(actor)

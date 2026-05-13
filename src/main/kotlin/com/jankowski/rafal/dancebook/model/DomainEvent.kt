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

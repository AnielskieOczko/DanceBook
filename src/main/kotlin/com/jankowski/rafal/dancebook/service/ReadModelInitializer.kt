package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import org.hibernate.Hibernate

/**
 * Open Session in View is off, so an entity returned from a service is usable only as far as it
 * was loaded inside the service's transaction. Search results and related notes are returned
 * out of short transactions of their own and are read later by templates and other callers, so
 * they are initialised here, inside that transaction, down to what a card or list would read.
 * Call it only while the transaction that loaded the entity is still open.
 *
 * Not loaded, on purpose: a figure's step sets and steps (large, and only the figure page needs
 * them) and a note's comments.
 */
internal object ReadModelInitializer {

    /** owner, dance type and its category, pinned figures with their catalog entry, type and category. */
    fun note(m: Material): Material {
        Hibernate.initialize(m.owner)
        danceType(m.danceType)
        Hibernate.initialize(m.figures)
        m.figures.forEach { pin ->
            Hibernate.initialize(pin.danceFigure)
            pin.danceFigure?.let { danceType(it.danceType) }
        }
        return m
    }

    /** dance type and its category, and who created it. */
    fun figure(f: DanceFigure): DanceFigure {
        danceType(f.danceType)
        Hibernate.initialize(f.createdBy)
        return f
    }

    private fun danceType(t: DanceType?) {
        Hibernate.initialize(t)
        Hibernate.initialize(t?.category)
    }
}

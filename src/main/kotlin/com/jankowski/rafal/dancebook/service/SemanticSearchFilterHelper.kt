package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.MedalLevel
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.Comparator
import java.util.UUID

/**
 * Filters and sorts semantic hits outside of controllers, ensuring exact filter and sort parity
 * with database-backed list queries.
 */
@Component
class SemanticSearchFilterHelper {

    fun filterAndSortNotes(
        notes: List<Material>,
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        minRating: Short? = null,
        sort: String? = null,
        excludeIds: Set<UUID> = emptySet()
    ): List<Material> {
        return notes
            .filter { note ->
                val id = note.id ?: return@filter false
                if (id in excludeIds) return@filter false
                if (!typeIds.isNullOrEmpty() && note.danceType?.id !in typeIds) return@filter false
                if (!categoryIds.isNullOrEmpty() && note.danceType?.category?.id !in categoryIds) return@filter false
                if (minRating != null && (note.rating == null || note.rating!! < minRating)) return@filter false
                true
            }
            .sortedWith(noteComparator(sort))
    }

    fun filterAndSortFigures(
        figures: List<DanceFigure>,
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        danceClass: DanceClass? = null,
        medalLevel: MedalLevel? = null,
        hasSteps: Boolean? = null,
        figureIdsWithSteps: Set<UUID> = emptySet(),
        sortBy: String? = null,
        excludeIds: Set<UUID> = emptySet()
    ): List<DanceFigure> {
        return figures
            .filter { figure ->
                val id = figure.id ?: return@filter false
                if (id in excludeIds) return@filter false
                if (!typeIds.isNullOrEmpty() && figure.danceType?.id !in typeIds) return@filter false
                if (!categoryIds.isNullOrEmpty() && figure.danceType?.category?.id !in categoryIds) return@filter false
                if (danceClass != null && figure.danceClass != danceClass) return@filter false
                if (medalLevel != null && figure.medalLevel != medalLevel) return@filter false
                if (hasSteps != null) {
                    val figureHasSteps = id in figureIdsWithSteps
                    if (hasSteps != figureHasSteps) return@filter false
                }
                true
            }
            .sortedWith(figureComparator(sortBy))
    }

    private fun noteComparator(sort: String?): Comparator<Material> {
        val nameAsc = Comparator<Material> { a, b -> a.name.orEmpty().compareTo(b.name.orEmpty(), ignoreCase = true) }
        val nameDesc = Comparator<Material> { a, b -> b.name.orEmpty().compareTo(a.name.orEmpty(), ignoreCase = true) }

        return when (sort) {
            "createdAt,asc" -> Comparator<Material> { a, b ->
                a.createdAt.compareTo(b.createdAt)
            }.thenComparing(nameAsc)
            "createdAt,desc" -> Comparator<Material> { a, b ->
                b.createdAt.compareTo(a.createdAt)
            }.thenComparing(nameAsc)
            "name,asc" -> nameAsc
            "name,desc" -> nameDesc
            "rating,desc" -> Comparator<Material> { a, b ->
                val ra = a.rating ?: Short.MIN_VALUE
                val rb = b.rating ?: Short.MIN_VALUE
                rb.compareTo(ra)
            }.thenComparing(nameAsc)
            "rating,asc" -> Comparator<Material> { a, b ->
                val ra = a.rating ?: Short.MAX_VALUE
                val rb = b.rating ?: Short.MAX_VALUE
                ra.compareTo(rb)
            }.thenComparing(nameAsc)
            "danceType.name,asc" -> Comparator<Material> { a, b ->
                a.danceType?.name.orEmpty().compareTo(b.danceType?.name.orEmpty(), ignoreCase = true)
            }.thenComparing(nameAsc)
            "danceType.name,desc" -> Comparator<Material> { a, b ->
                b.danceType?.name.orEmpty().compareTo(a.danceType?.name.orEmpty(), ignoreCase = true)
            }.thenComparing(nameAsc)
            "updatedAt,desc" -> Comparator<Material> { a, b ->
                val ua = a.updatedAt ?: LocalDateTime.MIN
                val ub = b.updatedAt ?: LocalDateTime.MIN
                ub.compareTo(ua)
            }.thenComparing(nameAsc)
            else -> Comparator<Material> { a, b ->
                b.createdAt.compareTo(a.createdAt)
            }.thenComparing(nameAsc)
        }
    }

    private fun figureComparator(sortBy: String?): Comparator<DanceFigure> {
        val nameAsc = Comparator<DanceFigure> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        val nameDesc = Comparator<DanceFigure> { a, b -> b.name.compareTo(a.name, ignoreCase = true) }

        return when (sortBy) {
            "name_asc" -> nameAsc
            "name_desc" -> nameDesc
            "style_asc" -> Comparator<DanceFigure> { a, b ->
                a.danceType?.name.orEmpty().compareTo(b.danceType?.name.orEmpty(), ignoreCase = true)
            }.thenComparing(nameAsc)
            "style_desc" -> Comparator<DanceFigure> { a, b ->
                b.danceType?.name.orEmpty().compareTo(a.danceType?.name.orEmpty(), ignoreCase = true)
            }.thenComparing(nameAsc)
            "class_asc" -> Comparator<DanceFigure> { a, b ->
                val ca = a.danceClass?.ordinal ?: Int.MAX_VALUE
                val cb = b.danceClass?.ordinal ?: Int.MAX_VALUE
                ca.compareTo(cb)
            }.thenComparing(nameAsc)
            "class_desc" -> Comparator<DanceFigure> { a, b ->
                val ca = a.danceClass?.ordinal ?: -1
                val cb = b.danceClass?.ordinal ?: -1
                cb.compareTo(ca)
            }.thenComparing(nameAsc)
            else -> Comparator<DanceFigure> { a, b ->
                a.danceType?.name.orEmpty().compareTo(b.danceType?.name.orEmpty(), ignoreCase = true)
            }.thenComparing(nameAsc)
        }
    }
}

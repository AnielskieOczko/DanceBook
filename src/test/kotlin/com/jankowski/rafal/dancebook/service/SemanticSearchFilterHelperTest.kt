package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.MedalLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class SemanticSearchFilterHelperTest {

    private val helper = SemanticSearchFilterHelper()

    private val standardCategory = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val latinCategory = DanceCategory().apply { id = UUID.randomUUID(); name = "Latin" }

    private val waltz = DanceType().apply { id = UUID.randomUUID(); name = "Waltz"; category = standardCategory }
    private val tango = DanceType().apply { id = UUID.randomUUID(); name = "Tango"; category = standardCategory }
    private val samba = DanceType().apply { id = UUID.randomUUID(); name = "Samba"; category = latinCategory }

    @Test
    fun `filterAndSortNotes filters by style, category, rating floor and excludeIds`() {
        val now = LocalDateTime.now()
        val n1 = Material().apply {
            id = UUID.randomUUID()
            name = "Note 1"
            danceType = waltz
            rating = 4
            createdAt = now.minusDays(1)
        }
        val n2 = Material().apply {
            id = UUID.randomUUID()
            name = "Note 2"
            danceType = tango
            rating = 3
            createdAt = now.minusDays(2)
        }
        val n3 = Material().apply {
            id = UUID.randomUUID()
            name = "Note 3"
            danceType = samba
            rating = 5
            createdAt = now.minusDays(3)
        }

        val all = listOf(n1, n2, n3)

        // Filter by typeIds
        val byStyle = helper.filterAndSortNotes(all, typeIds = listOf(waltz.id!!))
        assertEquals(listOf(n1), byStyle)

        // Filter by categoryIds (default sort is createdAt,desc: n1, n2)
        val byCat = helper.filterAndSortNotes(all, categoryIds = listOf(standardCategory.id!!))
        assertEquals(listOf(n1, n2), byCat)

        // Filter by minRating (default sort is createdAt,desc: n1, n3)
        val byRating = helper.filterAndSortNotes(all, minRating = 4)
        assertEquals(listOf(n1, n3), byRating)

        // Exclude ids
        val excluded = helper.filterAndSortNotes(all, excludeIds = setOf(n1.id!!))
        assertEquals(listOf(n2, n3), excluded)
    }

    @Test
    fun `filterAndSortNotes handles every sort order`() {
        val now = LocalDateTime.now()
        val n1 = Material().apply {
            id = UUID.randomUUID()
            name = "Bravo"
            danceType = waltz
            rating = 2
            createdAt = now.minusDays(2)
            updatedAt = now.minusDays(1)
        }
        val n2 = Material().apply {
            id = UUID.randomUUID()
            name = "Alpha"
            danceType = samba
            rating = 5
            createdAt = now.minusDays(1)
            updatedAt = now.minusDays(3)
        }
        val n3 = Material().apply {
            id = UUID.randomUUID()
            name = "Charlie"
            danceType = tango
            rating = 4
            createdAt = now.minusDays(3)
            updatedAt = now
        }

        val all = listOf(n1, n2, n3)

        assertEquals(listOf(n2, n1, n3), helper.filterAndSortNotes(all, sort = "createdAt,desc"))
        assertEquals(listOf(n3, n1, n2), helper.filterAndSortNotes(all, sort = "createdAt,asc"))
        assertEquals(listOf(n2, n1, n3), helper.filterAndSortNotes(all, sort = "name,asc"))
        assertEquals(listOf(n3, n1, n2), helper.filterAndSortNotes(all, sort = "name,desc"))
        assertEquals(listOf(n2, n3, n1), helper.filterAndSortNotes(all, sort = "rating,desc"))
        assertEquals(listOf(n1, n3, n2), helper.filterAndSortNotes(all, sort = "rating,asc"))
        assertEquals(listOf(n2, n3, n1), helper.filterAndSortNotes(all, sort = "danceType.name,asc")) // Samba, Tango, Waltz
        assertEquals(listOf(n1, n3, n2), helper.filterAndSortNotes(all, sort = "danceType.name,desc")) // Waltz, Tango, Samba
        assertEquals(listOf(n3, n1, n2), helper.filterAndSortNotes(all, sort = "updatedAt,desc"))
    }

    @Test
    fun `filterAndSortFigures filters by style, category, class, medal, steps and excludeIds`() {
        val f1 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Figure 1"
            danceType = tango // Tango sorts before Waltz
            danceClass = DanceClass.H
            medalLevel = MedalLevel.BRONZE
        }
        val f2 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Figure 2"
            danceType = waltz
            danceClass = DanceClass.E
            medalLevel = MedalLevel.SILVER
        }
        val f3 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Figure 3"
            danceType = samba
            danceClass = DanceClass.A
            medalLevel = MedalLevel.GOLD
        }

        val all = listOf(f1, f2, f3)
        val figureIdsWithSteps = setOf(f1.id!!)

        // Style
        assertEquals(listOf(f2), helper.filterAndSortFigures(all, typeIds = listOf(waltz.id!!)))

        // Category (Tango, then Waltz)
        assertEquals(listOf(f1, f2), helper.filterAndSortFigures(all, categoryIds = listOf(standardCategory.id!!)))

        // Class
        assertEquals(listOf(f2), helper.filterAndSortFigures(all, danceClass = DanceClass.E))

        // Medal
        assertEquals(listOf(f3), helper.filterAndSortFigures(all, medalLevel = MedalLevel.GOLD))

        // Steps
        assertEquals(listOf(f1), helper.filterAndSortFigures(all, hasSteps = true, figureIdsWithSteps = figureIdsWithSteps))
        assertEquals(listOf(f3, f2), helper.filterAndSortFigures(all, hasSteps = false, figureIdsWithSteps = figureIdsWithSteps)) // Samba, then Waltz

        // Exclude IDs (Samba, then Waltz)
        assertEquals(listOf(f3, f2), helper.filterAndSortFigures(all, excludeIds = setOf(f1.id!!)))
    }

    @Test
    fun `filterAndSortFigures handles every sort order`() {
        val f1 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Bravo"
            danceType = waltz
            danceClass = DanceClass.S
        }
        val f2 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Alpha"
            danceType = samba
            danceClass = DanceClass.H
        }
        val f3 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Charlie"
            danceType = tango
            danceClass = DanceClass.C
        }

        val all = listOf(f1, f2, f3)

        assertEquals(listOf(f2, f1, f3), helper.filterAndSortFigures(all, sortBy = "name_asc"))
        assertEquals(listOf(f3, f1, f2), helper.filterAndSortFigures(all, sortBy = "name_desc"))
        assertEquals(listOf(f2, f3, f1), helper.filterAndSortFigures(all, sortBy = "style_asc")) // Samba, Tango, Waltz
        assertEquals(listOf(f1, f3, f2), helper.filterAndSortFigures(all, sortBy = "style_desc")) // Waltz, Tango, Samba
        assertEquals(listOf(f2, f3, f1), helper.filterAndSortFigures(all, sortBy = "class_asc")) // H, C, S
        assertEquals(listOf(f1, f3, f2), helper.filterAndSortFigures(all, sortBy = "class_desc")) // S, C, H
        assertEquals(listOf(f2, f3, f1), helper.filterAndSortFigures(all, sortBy = null)) // default: style & name
    }
}

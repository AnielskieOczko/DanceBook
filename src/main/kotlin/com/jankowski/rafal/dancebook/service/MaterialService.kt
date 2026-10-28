package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface MaterialService {
    fun findById(id: UUID): Material
    fun findByIdForExport(id: UUID): Material
    fun create(request: MaterialRequest): Material
    fun update(id: UUID, request: MaterialRequest): Material
    fun delete(id: UUID)
    fun findAll(): List<Material>
    fun findAll(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        minRating: Short? = null,
        nameSearch: String? = null,
        pageable: Pageable
    ): Page<Material>
    fun findAll(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        minRating: Short? = null,
        nameSearch: String? = null,
        textSearch: String? = null,
        pageable: Pageable
    ): Page<Material>

    /**
     * One part (0-based [part], at most [size] notes) of what the Notes list shows for these filters, for a PDF
     * export. Unlike [findAll] it applies the ordinary-user visibility rule to admins too, so an export never
     * contains another user's private note. The order is fixed (newest first, then id), which keeps parts stable.
     */
    fun findForExport(
        typeIds: List<UUID>?,
        categoryIds: List<UUID>?,
        minRating: Short?,
        textSearch: String?,
        part: Int,
        size: Int
    ): Page<Material>

    fun downloadVideo(id: UUID, rangeHeader: String?): GoogleDriveService.DriveMediaDownload

    fun addFigure(materialId: UUID, request: FigureRequest): Figure
    fun updateFigure(materialId: UUID, figureId: UUID, request: FigureRequest): Figure
    fun removeFigure(materialId: UUID, figureId: UUID)
    fun findFiguresByMaterial(materialId: UUID): List<Figure>
    fun findFigureCounts(materialIds: Collection<UUID>): Map<UUID, Int>
    /** Notes the current user can see whose title or text contain every word of [query], newest first. */
    fun searchNotes(query: String?, figureId: UUID?, danceTypeId: UUID?, limit: Int): List<Material> =
        searchNotes(query, figureId, danceTypeId, limit, false)

    /** As above; with [ownedOnly] only notes the current user owns, never more than they could see. */
    fun searchNotes(query: String?, figureId: UUID?, danceTypeId: UUID?, limit: Int, ownedOnly: Boolean): List<Material>

    fun hasPrivateNotesMatchingFilter(
        owner: com.jankowski.rafal.dancebook.model.AppUser,
        typeIds: List<UUID>?,
        categoryIds: List<UUID>?,
        minRating: Short?,
        nameSearch: String?
    ): Boolean

    fun countByOwner(owner: com.jankowski.rafal.dancebook.model.AppUser): Long
}
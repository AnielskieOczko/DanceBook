package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Material
import java.util.UUID

interface KnowledgeRetrievalService {
    fun findRelatedNotesForMaterial(materialId: UUID, currentUser: AppUser?, limit: Int = 3): List<Material>
    fun findRelatedNotesForFigure(figureId: UUID, currentUser: AppUser?, limit: Int = 3): List<Material>
}

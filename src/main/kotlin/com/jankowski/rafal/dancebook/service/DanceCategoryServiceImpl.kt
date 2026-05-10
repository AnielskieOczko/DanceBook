package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceCategoryRequest
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DanceCategoryServiceImpl(
    private val danceCategoryRepository: DanceCategoryRepository,
    private val fileStorageService: FileStorageService
): DanceCategoryService {

    companion object {
        private val log = LoggerFactory.getLogger(DanceCategoryServiceImpl::class.java)
        private const val IMAGE_SUBDIR = "categories"
    }

    override fun findAll(): List<DanceCategory> {
        log.info("Retrieving all DanceCategories")
        return danceCategoryRepository.findAll()

    }

    override fun create(request: DanceCategoryRequest): DanceCategory {
        log.info("Creating new DanceCategory")
        val newDanceCategory = DanceCategory()
        newDanceCategory.name = request.name
        
        request.image?.let { file ->
            if (!file.isEmpty) {
                newDanceCategory.imageFilename = fileStorageService.storeFile(file, IMAGE_SUBDIR)
            }
        }
        
        return danceCategoryRepository.save(newDanceCategory)
    }

    override fun update(
        id: UUID,
        request: DanceCategoryRequest
    ): DanceCategory {
        val existing = findById(id)
        existing.name = request.name
        
        request.image?.let { file ->
            if (!file.isEmpty) {
                // Delete old image if it exists
                existing.imageFilename?.let { oldFile ->
                    fileStorageService.deleteFile(oldFile, IMAGE_SUBDIR)
                }
                existing.imageFilename = fileStorageService.storeFile(file, IMAGE_SUBDIR)
            }
        }
        
        return danceCategoryRepository.save(existing)
    }

    override fun delete(id: UUID) {
        log.info("Deleting DanceCategory")
        val existing = findById(id)
        
        existing.imageFilename?.let {
            fileStorageService.deleteFile(it, IMAGE_SUBDIR)
        }
        
        danceCategoryRepository.delete(existing)
    }

    override fun findById(id: UUID): DanceCategory {
        log.info("Retrieving DanceCategory with id: $id")
        val existing  = danceCategoryRepository.findById(id).orElseThrow { throw EntityNotFoundException("Could not find DanceCategory with id $id") }
        return existing
    }
}
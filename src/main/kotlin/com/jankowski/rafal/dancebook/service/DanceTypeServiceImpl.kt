package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceTypeRequest
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DanceTypeServiceImpl(
    private val danceTypeRepository: DanceTypeRepository,
): DanceTypeService {

    companion object {
        private val log = LoggerFactory.getLogger(DanceTypeServiceImpl::class.java)
    }

    override fun findAll(): List<DanceType> {
        log.info("Retrieving all DanceType")
        return danceTypeRepository.findAll()
    }

    override fun findById(id: UUID): DanceType {
        log.info("Retrieving DanceType with ID $id")
        val danceType = danceTypeRepository.findById(id).orElseThrow { EntityNotFoundException("DanceType with ID $id not found") }
        return danceType
    }

    override fun create(request: DanceTypeRequest): DanceType {
        log.info("Creating new DanceType")

        val newDanceType = DanceType()
        newDanceType.name = request.name

        return danceTypeRepository.save(newDanceType)
    }

    override fun update(id: UUID ,request: DanceTypeRequest): DanceType {
        log.info("Updating new DanceType with name ${request.name}")
        val existing = findById(id)
        existing.name = request.name
        return danceTypeRepository.save(existing)
    }

    override fun delete(danceTypeId: UUID) {
        val existing = findById(danceTypeId)
        danceTypeRepository.delete(existing)
    }

}
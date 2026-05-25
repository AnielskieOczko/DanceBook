package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureStepRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class SyllabusImporterIntegrationTest {

    @Autowired
    private lateinit var syllabusImporterService: SyllabusImporterService

    @Autowired
    private lateinit var danceFigureRepository: DanceFigureRepository

    @Autowired
    private lateinit var danceFigureStepRepository: DanceFigureStepRepository

    @Test
    @org.springframework.transaction.annotation.Transactional
    fun `should import full dataset and populate DB`() {
        val result = syllabusImporterService.importFromDataset("docs/dataset_website-content-crawler_2026-05-25_19-46-36-612.json")
        println("========================================================")
        println("Integration Import Result:")
        println("Figures Updated: ${result.figuresUpdated}")
        println("Steps Created  : ${result.stepsCreated}")
        println("Skipped        : ${result.skippedUnmatched}")
        println("Warnings Count : ${result.warnings.size}")
        println("========================================================")
        
        // Assert that we successfully updated some figures and created some steps
        assertTrue(result.figuresUpdated > 0, "Should have updated at least one figure")
        assertTrue(result.stepsCreated > 0, "Should have created at least one step")
        
        // Verify that steps were actually saved in the DB and are retrievable
        val stepsCount = danceFigureStepRepository.count()
        assertTrue(stepsCount > 0, "Steps should be saved in DB")
    }
}

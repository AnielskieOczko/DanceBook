package com.jankowski.rafal.dancebook.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

@Service
class FileStorageService(
    @Value("\${app.storage.upload-dir:uploads}")
    private val uploadDir: String
) {

    fun storeFile(file: MultipartFile, subDirectory: String): String {
        if (file.isEmpty) {
            throw IllegalArgumentException("Failed to store empty file.")
        }

        val originalFilename = file.originalFilename ?: "unknown.png"
        val extension = originalFilename.substringAfterLast('.', "png")
        val newFilename = "${UUID.randomUUID()}.$extension"

        val targetLocation = Paths.get(uploadDir, subDirectory).toAbsolutePath().normalize()
        
        if (!Files.exists(targetLocation)) {
            Files.createDirectories(targetLocation)
        }

        val destinationFile = targetLocation.resolve(newFilename)
        
        // Security check
        if (!destinationFile.parent.equals(targetLocation)) {
            throw SecurityException("Cannot store file outside current directory.")
        }

        Files.copy(file.inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING)
        
        return newFilename
    }

    fun deleteFile(filename: String, subDirectory: String) {
        val targetLocation = Paths.get(uploadDir, subDirectory).toAbsolutePath().normalize()
        val fileToDelete = targetLocation.resolve(filename)
        Files.deleteIfExists(fileToDelete)
    }
}

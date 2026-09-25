package com.jankowski.rafal.dancebook.scripts

import com.jankowski.rafal.dancebook.DanceBookApplication
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

fun main(args: Array<String>) {
    val context = SpringApplicationBuilder(DanceBookApplication::class.java)
        .web(WebApplicationType.NONE)
        .run(*args)

    try {
        val googleDriveService = context.getBean(GoogleDriveService::class.java)
        println("Starting one-off permissions cleanup on Google Drive folder...")
        val result = googleDriveService.cleanPermissions()
        println("Finished permissions cleanup.")
        println("Files scanned: ${result.filesScanned}")
        println("Public permissions removed: ${result.permissionsRemoved}")
        println("Modified files: ${result.modifiedFileIds.size}")
    } finally {
        context.close()
    }
}

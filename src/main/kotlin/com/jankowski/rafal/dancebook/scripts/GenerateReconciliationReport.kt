package com.jankowski.rafal.dancebook.scripts

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jankowski.rafal.dancebook.service.SyllabusImporterService.AiParsedFigureDto
import java.io.File
import java.nio.file.Paths

fun main() {
    val csvFile = Paths.get("docs/figures steps/dancebook_public_dance_figure.csv").toFile()
    if (!csvFile.exists()) {
        System.err.println("Error: CSV reference file not found at ${csvFile.absolutePath}")
        System.exit(1)
    }

    val parsedDir = File("docs/figures steps/parsed")
    if (!parsedDir.exists() || !parsedDir.isDirectory) {
        System.err.println("Error: Parsed figures directory not found at docs/figures steps/parsed")
        System.exit(1)
    }

    val danceTypeMap = mapOf(
        "e5893ef5-b115-4685-bf38-04ead55743a3" to "Waltz",
        "b742edb9-8245-4cfe-85c4-5b73769c17b5" to "Tango",
        "f9651c07-7068-4fbb-a96a-aa9101399816" to "Viennese Waltz",
        "8a31b785-c726-4b8b-9fe6-b4c538b7f890" to "Foxtrot",
        "ac9286d6-5c7f-4ba2-9157-39c1e78801a3" to "Quickstep",
        "69d18fcf-92da-4748-b4b2-e532d8e8d89d" to "Cha Cha",
        "e182e966-64b2-4aaa-9909-da1aceaed6ba" to "Samba",
        "4ca62d38-30de-4759-aea0-668fdb49d7b8" to "Rumba",
        "e61fd652-94f4-4ee4-86c2-87554fc11d2d" to "Paso Doble",
        "f9053836-24d1-433b-a228-8ef0d62e1865" to "Jive"
    )

    // Load CSV reference figures
    data class CsvFigure(val id: String, val name: String, val style: String, val level: String)
    val csvFigures = mutableListOf<CsvFigure>()
    csvFile.readLines().drop(1).forEach { line ->
        if (line.isNotBlank()) {
            val parts = parseCsvLine(line)
            if (parts.size > 3) {
                val id = parts[0]
                val name = parts[1]
                val danceTypeId = parts[2]
                val levelCode = parts[3]
                val style = danceTypeMap[danceTypeId] ?: "Unknown"
                val level = when (levelCode.uppercase()) {
                    "H" -> "Newcomer/Bronze"
                    "F" -> "Silver/Gold"
                    else -> "Standard"
                }
                csvFigures.add(CsvFigure(id, name, style, level))
            }
        }
    }

    // Load all individual parsed JSON files
    val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    
    data class ParsedFigureInfo(
        val name: String,
        val originalName: String,
        val danceType: String,
        val level: String,
        val stepCount: Int,
        val commentCount: Int,
        val url: String,
        val relativePath: String
    )
    
    val parsedFigures = mutableListOf<ParsedFigureInfo>()

    (1..10).forEach { chunk ->
        val chunkDir = parsedDir.resolve("chunk_$chunk")
        if (chunkDir.exists() && chunkDir.isDirectory) {
            val jsonFiles = chunkDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            for (file in jsonFiles) {
                try {
                    val content = file.readText().trim()
                    val dtoList = if (content.startsWith("[")) {
                        objectMapper.readValue<List<AiParsedFigureDto>>(file)
                    } else {
                        listOf(objectMapper.readValue<AiParsedFigureDto>(file))
                    }
                    for (dto in dtoList) {
                        val name = dto.name ?: continue
                        val danceType = dto.dance_type ?: continue
                        val level = dto.level ?: ""
                        val steps = dto.steps ?: emptyList()
                        val stepCount = steps.size
                        val commentCount = steps.sumOf { it.comments?.size ?: 0 }
                        val url = dto.urls?.firstOrNull() ?: dto.url ?: ""
                        val relPath = "docs/figures steps/parsed/chunk_$chunk/${file.name}"
                        val mappedStyle = mapDanceTypeJsonToDbName(danceType)
                        parsedFigures.add(ParsedFigureInfo(
                            name = name,
                            originalName = name,
                            danceType = mappedStyle,
                            level = level,
                            stepCount = stepCount,
                            commentCount = commentCount,
                            url = url,
                            relativePath = relPath
                        ))
                    }
                } catch (e: Exception) {
                    // Skip invalid JSON
                }
            }
        }
    }

    // Perform matching logic
    data class ReconciliationRecord(
        val dbId: String?,
        val dbName: String?,
        val style: String,
        val dbLevel: String?,
        val isUpdated: Boolean,
        val scrapedName: String?,
        val level: String?,
        val stepCount: Int?,
        val commentCount: Int?,
        val url: String?,
        val parsedFilePath: String?
    )

    val matchedScrapedIndexes = mutableSetOf<Int>()
    val reportRecords = mutableListOf<ReconciliationRecord>()

    // 1. Loop through DB figures and check for matches
    for (csvFig in csvFigures) {
        // Look for matching parsed figure
        var matchedIndex = -1
        var bestMatch: ParsedFigureInfo? = null
        
        for ((idx, parsedFig) in parsedFigures.withIndex()) {
            if (idx in matchedScrapedIndexes) continue
            if (!parsedFig.danceType.equals(csvFig.style, ignoreCase = true)) continue
            
            // Re-use matching helper
            val matchName = findMatchingReferenceNameForReport(parsedFig.name, csvFig.style, csvFig.name)
            if (matchName != null) {
                bestMatch = parsedFig
                matchedIndex = idx
                break
            }
        }

        if (bestMatch != null) {
            matchedScrapedIndexes.add(matchedIndex)
            reportRecords.add(ReconciliationRecord(
                dbId = csvFig.id,
                dbName = csvFig.name,
                style = csvFig.style,
                dbLevel = csvFig.level,
                isUpdated = true,
                scrapedName = bestMatch.name,
                level = bestMatch.level,
                stepCount = bestMatch.stepCount,
                commentCount = bestMatch.commentCount,
                url = bestMatch.url,
                parsedFilePath = bestMatch.relativePath
            ))
        } else {
            reportRecords.add(ReconciliationRecord(
                dbId = csvFig.id,
                dbName = csvFig.name,
                style = csvFig.style,
                dbLevel = csvFig.level,
                isUpdated = false,
                scrapedName = null,
                level = null,
                stepCount = null,
                commentCount = null,
                url = null,
                parsedFilePath = null
            ))
        }
    }

    // 2. Loop through remaining parsed figures that did not match any standard database figures
    for ((idx, parsedFig) in parsedFigures.withIndex()) {
        if (idx in matchedScrapedIndexes) continue
        reportRecords.add(ReconciliationRecord(
            dbId = null,
            dbName = null,
            style = parsedFig.danceType,
            dbLevel = null,
            isUpdated = false,
            scrapedName = parsedFig.name,
            level = parsedFig.level,
            stepCount = parsedFig.stepCount,
            commentCount = parsedFig.commentCount,
            url = parsedFig.url,
            parsedFilePath = parsedFig.relativePath
        ))
    }

    // Sort report: Predefined DB figures first, then new scraped figures
    reportRecords.sortWith(compareBy<ReconciliationRecord> { it.dbName == null }
        .thenBy { it.style }
        .thenBy { it.dbName ?: it.scrapedName })

    // Generate CSV report
    val csvOutputFile = Paths.get("docs/figures steps/reconciliation_report.csv").toFile()
    val csvHeader = "db_id,db_name,dance_style,db_level,is_updated,scraped_name,scraped_level,step_count,comment_count,url,parsed_file_path"
    val csvLines = mutableListOf(csvHeader)
    for (rec in reportRecords) {
        val line = listOf(
            rec.dbId ?: "",
            rec.dbName ?: "",
            rec.style,
            rec.dbLevel ?: "",
            if (rec.isUpdated) "Yes" else "No",
            rec.scrapedName ?: "",
            rec.level ?: "",
            rec.stepCount?.toString() ?: "",
            rec.commentCount?.toString() ?: "",
            rec.url ?: "",
            rec.parsedFilePath ?: ""
        ).joinToString(",") { escapeCsvField(it) }
        csvLines.add(line)
    }
    csvOutputFile.writeText(csvLines.joinToString("\n"))
    println("Generated CSV report: ${csvOutputFile.absolutePath}")

    // Generate Markdown report
    val mdOutputFile = Paths.get("docs/figures steps/reconciliation_report.md").toFile()
    val mdBuilder = java.lang.StringBuilder()
    mdBuilder.append("# Dance Figures Reconciliation Report\n\n")
    mdBuilder.append("This report lists all standard database figures and details which of them were successfully matched and populated by AI-parsed steps. It also identifies all unmatched scraped figures, helping you manually reconcile naming differences.\n\n")
    
    val totalDb = csvFigures.size
    val totalUpdated = reportRecords.count { it.dbName != null && it.isUpdated }
    val totalUnupdated = totalDb - totalUpdated
    val totalUnmatchedScraped = reportRecords.count { it.dbName == null }

    mdBuilder.append("## Statistics Summary\n\n")
    mdBuilder.append("| Metric | Count |\n")
    mdBuilder.append("| :--- | :---: |\n")
    mdBuilder.append("| **Total Standard DB Figures** | $totalDb |\n")
    mdBuilder.append("| **Figures Updated with Steps/Metadata** | $totalUpdated (${String.format("%.1f", (totalUpdated.toFloat()/totalDb.toFloat()) * 100.0f)}%) |\n")
    mdBuilder.append("| **Figures Missing Steps/Metadata (Unupdated)** | $totalUnupdated (${String.format("%.1f", (totalUnupdated.toFloat()/totalDb.toFloat()) * 100.0f)}%) |\n")
    mdBuilder.append("| **Unmatched Scraped Figures (Potential New Figures)** | $totalUnmatchedScraped |\n\n")

    mdBuilder.append("## 1. Standard Figures Database List\n\n")
    mdBuilder.append("Below is the status of the predefined figures from the database, indicating if they got updated by the AI-parsed dataset.\n\n")
    mdBuilder.append("| Database Name | Dance Style | DB Level | Updated? | Steps | Comments | Matched Scraped Name | Source File |\n")
    mdBuilder.append("| :--- | :--- | :--- | :---: | :---: | :---: | :--- | :--- |\n")
    for (rec in reportRecords.filter { it.dbName != null }) {
        val fileLink = if (rec.parsedFilePath != null) "[${rec.parsedFilePath.substringAfterLast("/")}](file:///${Paths.get("").toAbsolutePath().toString()}/${rec.parsedFilePath})" else ""
        mdBuilder.append("| ${rec.dbName} | ${rec.style} | ${rec.dbLevel} | ${if (rec.isUpdated) "✅ **Yes**" else "❌ No"} | ${rec.stepCount ?: 0} | ${rec.commentCount ?: 0} | ${rec.scrapedName ?: ""} | $fileLink |\n")
    }

    mdBuilder.append("\n## 2. Unmatched Scraped Figures (Potential New Figures)\n\n")
    mdBuilder.append("Below are the parsed figures that could **not** be mapped to any standard database figure, likely due to spelling discrepancies or entirely new syllabus variations. You can use this table to create mapping aliases.\n\n")
    mdBuilder.append("| Scraped Name | Dance Style | Scraped Level | Steps | Comments | Source File | Reference URL |\n")
    mdBuilder.append("| :--- | :--- | :--- | :---: | :---: | :--- | :--- |\n")
    for (rec in reportRecords.filter { it.dbName == null }) {
        val fileLink = if (rec.parsedFilePath != null) "[${rec.parsedFilePath.substringAfterLast("/")}](file:///${Paths.get("").toAbsolutePath().toString()}/${rec.parsedFilePath})" else ""
        val urlLink = if (rec.url != null) "[Reference](${rec.url})" else ""
        mdBuilder.append("| **${rec.scrapedName}** | ${rec.style} | ${rec.level} | ${rec.stepCount ?: 0} | ${rec.commentCount ?: 0} | $fileLink | $urlLink |\n")
    }

    mdOutputFile.writeText(mdBuilder.toString())
    println("Generated Markdown report: ${mdOutputFile.absolutePath}")
}

private fun escapeCsvField(field: String): String {
    if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
    return field
}

private fun normalizeNameForReport(name: String, danceTypeName: String): String {
    var n = removeAccentsForReport(name).lowercase(java.util.Locale.ROOT)
    n = n.replace("promenade position", "pp")
    n = n.replace(" and ", " ").replace(" & ", " ")
    val stylePrefix = danceTypeName.lowercase(java.util.Locale.ROOT) + " "
    if (n.startsWith(stylePrefix)) {
        n = n.substring(stylePrefix.length)
    }
    n = n.replace(Regex("[^a-z0-9]"), "")
    if (n.endsWith("s") && n.length > 4) {
        n = n.substring(0, n.length - 1)
    }
    return n
}

private fun removeAccentsForReport(input: String): String {
    val temp = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
    return temp.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
}

private fun stripParenthesesForReport(input: String): String {
    return input.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
}

private fun getParenthesesContentForReport(input: String): String? {
    val match = Regex("\\(([^)]+)\\)").find(input)
    return match?.groupValues?.get(1)?.trim()
}

private val figureAliasesForReport = mapOf(
    "Samba" to mapOf(
        "rolling off the arm" to "Rolling of the Arm",
        "samba walks" to "Samba Walks in PP Position (RF and LF) (Promenade Samba Walks)",
        "traveling bota fogos back" to "Travelling Bota Fogos Back",
        "traveling bota fogos forward" to "Travelling Bota Fogos Forward",
        "volta movements" to "Volta Movement (ogólnie)",
        "whisk" to "Whisk to Right and Left Underarm Turn (Volta Spot Turn to R and L for Lady)",
        "bota fogos to promenade and counter promenade" to "Bota Fogos To PP and CPP (Promenade Botafogos)",
        "cruzados walks and locks" to "Cruzado Walks and Locks (in Shadow Position)"
    ),
    "Rumba" to mapOf(
        "progressive walks" to "Progressive Walks Forward or Backward",
        "rope spinning" to "Spiral Turns (Spiral, Curl and Rope Spinning)",
        "spiral" to "Spiral Turns (Spiral, Curl and Rope Spinning)",
        "spot turns (including switch turns, underarm turns)" to "Spot Turns to L or R",
        "shoulder to shoulder" to "Shoulder to Shoulder (Left Side and Right Side) Development",
        "advanced hip twists" to "Hip Twists (Advanced, Continuous and Circular)",
        "continuous hip twists" to "Hip Twists (Advanced, Continuous and Circular)",
        "circular hip twists" to "Hip Twists (Advanced, Continuous and Circular)",
        "curl" to "Spiral Turns (Spiral, Curl and Rope Spinning)"
    ),
    "Cha Cha" to mapOf(
        "natural opening out movement" to "Natural Opening Out Movement (Opening Out to Right)",
        "new york" to "New York (To LSP or RSP) Check from OCPP or OPP",
        "natural top" to "Natural Top Development (Underarm Turn)",
        "rope spinning" to "Spiral Turns (Spiral, Curl and Rope Spinning)",
        "shoulder to shoulder" to "Shoulder to Shoulder (Left Side and Right Side) Development",
        "side step" to "Side Steps (To Left or Right)",
        "spiral" to "Spiral Turns (Spiral, Curl and Rope Spinning)",
        "spot turn" to "Spot Turns to L or R (Including Switch and Underarm Turns) Developments",
        "spot turns, switch turns, underarm turns" to "Spot Turns to L or R (Including Switch and Underarm Turns) Developments",
        "time step" to "Time Steps (Basic in Place, Side Basic)",
        "curl" to "Spiral Turns (Spiral, Curl and Rope Spinning)",
        "hip twist spiral" to "Close Hip Twist Spiral"
    ),
    "Jive" to mapOf(
        "change of places right to left" to "Change of Place R to L",
        "change of places left to right" to "Change of Place L to R",
        "fallaway throwaway" to "Fallaway Throwaway Overturned Fallaway Throwaway",
        "rolling off the arm" to "Rolling of the Arm",
        "whip" to "Whip Double Cross Whip"
    ),
    "Tango" to mapOf(
        "open/closed finish" to "Open Finish",
        "open reverse turn, lady inline" to "Open Reverse, Lady in Line (Closed Finish)",
        "open reverse turn, lady outside" to "Open Reverse, Lady Outside (Open Finish)",
        "the chase" to "Chase"
    ),
    "Waltz" to mapOf(
        "open impetus" to "Open Impetus Turn",
        "closed impetus" to "Impetus Turn (Closed)",
        "turning lock to r" to "Turning Lock",
        "open impetus and wing" to "Wing Following Open Impetus Turn",
        "open telemark and cross hesitation" to "Open Telemark into Cross Hesitation",
        "open impetus and cross hesitation" to "Cross Hesitation after Open Impetus Turn"
    ),
    "Foxtrot" to mapOf(
        "open impetus" to "Impetus Turn (Open)",
        "closed telemark" to "Telemark (Closed)",
        "open telemark, natural turn to outside swivel and feather ending" to "Open Telemark Natural Turn Outside Swivel Feather Ending"
    )
)

private fun findMatchingReferenceNameForReport(
    scrapedName: String,
    danceTypeName: String,
    dbName: String
): String? {
    val lowerScraped = scrapedName.lowercase(java.util.Locale.ROOT).trim()
    val lowerDb = dbName.lowercase(java.util.Locale.ROOT).trim()

    // 1. Check explicit alias map
    val aliasDbName = figureAliasesForReport[danceTypeName]?.get(lowerScraped)
    if (aliasDbName != null && aliasDbName.equals(dbName, ignoreCase = true)) {
        return dbName
    }

    // 2. Check normalized match
    val normScraped = normalizeNameForReport(scrapedName, danceTypeName)
    val normDb = normalizeNameForReport(dbName, danceTypeName)
    if (normScraped == normDb) return dbName

    // 3. Check stripped match
    val strippedScraped = stripParenthesesForReport(scrapedName)
    val normStrippedScraped = normalizeNameForReport(strippedScraped, danceTypeName)
    val strippedDb = stripParenthesesForReport(dbName)
    val normStrippedDb = normalizeNameForReport(strippedDb, danceTypeName)
    if (normStrippedScraped == normStrippedDb) return dbName

    // 4. Check parenthetical matching
    val parentheticalContentDb = getParenthesesContentForReport(dbName)
    if (parentheticalContentDb != null) {
        val normParentheticalDb = normalizeNameForReport(parentheticalContentDb, danceTypeName)
        if (normParentheticalDb == normScraped) return dbName
    }

    return null
}

private fun mapDanceTypeJsonToDbName(jsonDanceType: String): String {
    return when (jsonDanceType.uppercase()) {
        "WALTZ" -> "Waltz"
        "TANGO" -> "Tango"
        "VIENNESE_WALTZ" -> "Viennese Waltz"
        "SLOW_FOXTROT", "FOXTROT" -> "Foxtrot"
        "QUICKSTEP" -> "Quickstep"
        "CHA_CHA_CHA", "CHA_CHA" -> "Cha Cha"
        "SAMBA" -> "Samba"
        "RUMBA" -> "Rumba"
        "PASO_DOBLE" -> "Paso Doble"
        "JIVE" -> "Jive"
        else -> jsonDanceType
    }
}

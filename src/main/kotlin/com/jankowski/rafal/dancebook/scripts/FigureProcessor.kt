package com.jankowski.rafal.dancebook.scripts

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

fun main(args: Array<String>) {
    var chunkStr: String? = null
    var model = "nvidia/nemotron-3-nano-30b-a3b:free"
    var overwrite = false
    var limit = -1

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--chunk", "-c" -> {
                chunkStr = args.getOrNull(i + 1)
                i += 2
            }
            "--model", "-m" -> {
                model = args.getOrNull(i + 1) ?: model
                i += 2
            }
            "--overwrite", "-o" -> {
                overwrite = true
                i += 1
            }
            "--limit", "-l" -> {
                limit = args.getOrNull(i + 1)?.toIntOrNull() ?: -1
                i += 2
            }
            else -> {
                println("Unknown argument: ${args[i]}")
                i += 1
            }
        }
    }

    val finalChunkStr = chunkStr
    if (finalChunkStr == null) {
        println("Usage: ./gradlew processFigures --args=\"--chunk <1-10|all> [--model <model>] [--overwrite] [--limit <n>]\"")
        return
    }

    val chunksToProcess = if (finalChunkStr == "all") {
        (1..10).toList()
    } else {
        val chunkNum = finalChunkStr.toIntOrNull()
        if (chunkNum != null && chunkNum in 1..10) {
            listOf(chunkNum)
        } else {
            null
        }
    }

    if (chunksToProcess == null) {
        println("Error: Invalid chunk value '$finalChunkStr'. Must be a number between 1 and 10, or 'all'.")
        return
    }

    val apiKey = System.getenv("OPENROUTER_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Error: OPENROUTER_API_KEY environment variable is not set.")
        System.exit(1)
    }

    // 1. Load CSV Reference Figures
    val csvFile = Paths.get("docs/figures steps/dancebook_public_dance_figure.csv").toFile()
    if (!csvFile.exists()) {
        System.err.println("Error: CSV file not found at ${csvFile.absolutePath}")
        System.exit(1)
    }

    val danceTypeMap = mapOf(
        "e5893ef5-b115-4685-bf38-04ead55743a3" to "WALTZ",
        "b742edb9-8245-4cfe-85c4-5b73769c17b5" to "TANGO",
        "f9651c07-7068-4fbb-a96a-aa9101399816" to "VIENNESE_WALTZ",
        "8a31b785-c726-4b8b-9fe6-b4c538b7f890" to "SLOW_FOXTROT",
        "ac9286d6-5c7f-4ba2-9157-39c1e78801a3" to "QUICKSTEP",
        "69d18fcf-92da-4748-b4b2-e532d8e8d89d" to "CHA_CHA_CHA",
        "e182e966-64b2-4aaa-9909-da1aceaed6ba" to "SAMBA",
        "4ca62d38-30de-4759-aea0-668fdb49d7b8" to "RUMBA",
        "e61fd652-94f4-4ee4-86c2-87554fc11d2d" to "PASO_DOBLE",
        "f9053836-24d1-433b-a228-8ef0d62e1865" to "JIVE"
    )

    println("Reading standard figure list from CSV...")
    val referenceFigures = mutableListOf<String>()
    val referenceNamesSet = mutableSetOf<String>()
    val csvReferenceFigures = mutableListOf<CsvReferenceFigure>()
    csvFile.readLines().drop(1).forEach { line ->
        if (line.isNotBlank()) {
            val parts = parseCsvLine(line)
            if (parts.size > 2) {
                val name = parts[1]
                referenceNamesSet.add(name.trim().lowercase())
                val danceTypeId = parts[2]
                val danceName = danceTypeMap[danceTypeId] ?: "UNKNOWN"
                val normalizedDanceType = mapDanceTypeJsonToDbName(danceName)
                csvReferenceFigures.add(CsvReferenceFigure(name, normalizedDanceType))
                referenceFigures.add("- $name ($danceName)")
            }
        }
    }
    val referenceFiguresText = referenceFigures.joinToString("\n")

    // 2. Load Base Prompt
    val promptFile = Paths.get("docs/figures steps/AGENT_PROMPT.md").toFile()
    if (!promptFile.exists()) {
        System.err.println("Error: Prompt template file not found at ${promptFile.absolutePath}")
        System.exit(1)
    }
    val baseSystemPrompt = promptFile.readText()

    val systemPrompt = """
        $baseSystemPrompt
        
        ## Authoritative Figure Names Reference
        Below is the complete list of standard figure names currently stored in the database, along with their respective dance styles. You MUST use these exact names when standardizing figures in your output (case-insensitive, ignoring minor formatting differences):
        $referenceFiguresText
    """.trimIndent()

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val globalNewFigures = mutableListOf<Map<String, String>>()

    for (chunk in chunksToProcess) {
        println("\n==================================================")
        println("Processing chunk $chunk...")
        println("==================================================")

        // 3. Load Chunk Data
        val chunkFile = Paths.get("docs/figures steps/chunks/chunk_$chunk.json").toFile()
        if (!chunkFile.exists()) {
            System.err.println("Error: Chunk file not found at ${chunkFile.absolutePath}")
            continue
        }

        val listType = objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java)
        val rawFigures: List<Map<String, String>> = objectMapper.readValue(chunkFile, listType)

        println("Loaded ${rawFigures.size} figures from chunk $chunk")

        val parsedDir = Paths.get("docs/figures steps/parsed/chunk_$chunk")
        Files.createDirectories(parsedDir)

        var processedCount = 0
        for ((index, rawRecord) in rawFigures.withIndex()) {
            if (limit > 0 && processedCount >= limit) {
                println("Limit of $limit figures reached for chunk $chunk. Skipping remaining figures.")
                break
            }

            val url = rawRecord["url"] ?: continue
            val text = rawRecord["text"] ?: continue
            val filename = "${sanitizeUrlToFilename(url)}.json"
            val outputFile = parsedDir.resolve(filename).toFile()

            if (outputFile.exists() && !overwrite) {
                println("[Chunk $chunk: ${index + 1}/${rawFigures.size}] Skipping $url (already parsed)")
                continue
            }

            println("[Chunk $chunk: ${index + 1}/${rawFigures.size}] Processing $url...")

            val userPrompt = objectMapper.writeValueAsString(mapOf(
                "url" to url,
                "text" to text
            ))

            val payload = mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "response_format" to mapOf("type" to "json_object"),
                "reasoning" to mapOf("effort" to "medium")
            )

            val requestBody = objectMapper.writeValueAsString(payload)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://github.com/apify/agent-skills")
                .header("X-Title", "DanceBook Figures Parser")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build()

            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    System.err.println("API Error: HTTP ${response.statusCode()}: ${response.body()}")
                    System.exit(1)
                }

                val responseNode = objectMapper.readTree(response.body())
                val messageNode = responseNode.path("choices").get(0)?.path("message")
                
                // Extract and print reasoning details if available
                val reasoning = messageNode?.path("reasoning")?.asText()?.takeIf { it.isNotBlank() }
                    ?: messageNode?.path("reasoning_details")?.asText()?.takeIf { it.isNotBlank() }
                if (reasoning != null) {
                    println("--- Reasoning Process ---")
                    println(reasoning)
                    println("-------------------------")
                }

                val content = messageNode?.path("content")?.asText()
                if (content != null) {
                    val cleanedJson = cleanJsonContent(content)
                    outputFile.writeText(cleanedJson)
                    println("Saved: $filename")
                    processedCount++
                    
                    // Rate limit spacing
                    Thread.sleep(1000)
                } else {
                    System.err.println("Error: Could not extract content from API response: ${response.body()}")
                    System.exit(1)
                }
            } catch (e: Exception) {
                System.err.println("HTTP request failed: ${e.message}")
                e.printStackTrace()
                System.exit(1)
            }
        }

        // 4. Merge parsed results per chunk
        println("All chunk $chunk items processed. Merging into single parsed file...")
        val mergedFile = Paths.get("docs/figures steps/parsed/chunk_${chunk}_parsed.json").toFile()
        mergedFile.parentFile.mkdirs()
        val allFigures = mutableListOf<Any>()

        val matchedList = mutableListOf<String>()
        val unmatchedList = mutableListOf<String>()

        val files = parsedDir.toFile().listFiles { _, name -> name.endsWith(".json") }
        files?.sortedBy { it.name }?.forEach { file ->
            try {
                val jsonContent = file.readText().trim()
                val parsedList = if (jsonContent.startsWith("[")) {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(file, List::class.java) as List<Map<String, Any>>
                } else if (jsonContent.startsWith("{")) {
                    @Suppress("UNCHECKED_CAST")
                    listOf(objectMapper.readValue(file, Map::class.java) as Map<String, Any>)
                } else {
                    emptyList()
                }

                val updatedParsedList = mutableListOf<Map<String, Any>>()
                var fileModified = false

                for (fig in parsedList) {
                    val figName = fig["name"] as? String ?: "Unknown"
                    val danceType = fig["dance_type"] as? String ?: "Unknown"
                    val figUrl = fig["url"] as? String ?: ""
                    
                    val dbDanceTypeName = mapDanceTypeJsonToDbName(danceType)
                    val matchedRefFigureName = findMatchingReferenceFigureName(figName, dbDanceTypeName, csvReferenceFigures)
                    
                    val finalFigName = matchedRefFigureName ?: figName
                    val mutableFig = fig.toMutableMap()
                    if (matchedRefFigureName != null && matchedRefFigureName != figName) {
                        mutableFig["name"] = matchedRefFigureName
                        fileModified = true
                    }
                    updatedParsedList.add(mutableFig)
                    allFigures.add(mutableFig)
                    
                    if (matchedRefFigureName != null) {
                        matchedList.add("$finalFigName ($danceType)")
                    } else {
                        unmatchedList.add("$finalFigName ($danceType) -> URL: $figUrl")
                        val alreadyAdded = globalNewFigures.any { it["name"] == finalFigName && it["dance_type"] == danceType }
                        if (!alreadyAdded) {
                            globalNewFigures.add(mapOf(
                                "name" to finalFigName,
                                "dance_type" to danceType,
                                "url" to figUrl
                            ))
                        }
                    }
                }
                if (fileModified) {
                    val updatedContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        if (jsonContent.startsWith("[")) updatedParsedList else updatedParsedList[0]
                    )
                    file.writeText(updatedContent)
                }
            } catch (e: Exception) {
                println("Warning: Failed to parse individual file ${file.name} as JSON. Skipping: ${e.message}")
            }
        }

        val mergedJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(allFigures)
        mergedFile.writeText(mergedJson)
        println("Successfully merged ${allFigures.size} figures into: ${mergedFile.absolutePath}")

        println("\n==================================================")
        println("Chunk $chunk Match Report:")
        println("Total Parsed Figures: ${allFigures.size}")
        println("Matched with Database: ${matchedList.size}")
        println("New (Unmatched) Figures: ${unmatchedList.size}")
        if (unmatchedList.isNotEmpty()) {
            println("New Figures List:")
            unmatchedList.forEach { println("  * $it") }
        }
        println("==================================================")
    }

    val newFiguresFile = Paths.get("docs/figures steps/parsed/new_figures.json").toFile()
    val newFiguresJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(globalNewFigures)
    newFiguresFile.writeText(newFiguresJson)
    println("\nSaved ${globalNewFigures.size} new/unmatched figures to: ${newFiguresFile.absolutePath}")
}

fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    var inQuotes = false
    val current = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '"') {
            if (i + 1 < line.length && line[i + 1] == '"') {
                current.append('"') // Escaped quote
                i++
            } else {
                inQuotes = !inQuotes
            }
        } else if (c == ',' && !inQuotes) {
            result.add(current.toString().trim())
            current.clear()
        } else {
            current.append(c)
        }
        i++
    }
    result.add(current.toString().trim())
    return result
}

fun sanitizeUrlToFilename(url: String): String {
    val cleanUrl = url.removeSuffix("/")
    val parts = cleanUrl.split("/")
    if (parts.size >= 2) {
        val dance = parts[parts.size - 2]
        val figure = parts[parts.size - 1]
        return "${dance}_${figure}"
    }
    return url.replace(Regex("[^a-zA-Z0-9]"), "_")
}

fun cleanJsonContent(content: String): String {
    var trimmed = content.trim()
    if (trimmed.startsWith("```")) {
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd != -1) {
            trimmed = trimmed.substring(firstLineEnd).trim()
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length - 3).trim()
        }
    }
    return trimmed
}

private data class CsvReferenceFigure(
    val name: String,
    val danceType: String
)

private val figureAliases = mapOf(
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

private fun normalizeName(name: String, danceTypeName: String): String {
    var n = removeAccents(name).lowercase(java.util.Locale.ROOT)
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

private fun stripParentheses(input: String): String {
    return input.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
}

private fun getParenthesesContent(input: String): String? {
    val match = Regex("\\(([^)]+)\\)").find(input)
    return match?.groupValues?.get(1)?.trim()
}

private fun removeAccents(input: String): String {
    val temp = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
    return temp.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
}

private fun findMatchingReferenceFigureName(
    scrapedName: String,
    danceTypeName: String,
    referenceFigures: List<CsvReferenceFigure>
): String? {
    val lowerScraped = scrapedName.lowercase(java.util.Locale.ROOT).trim()
    val existingFiguresForStyle = referenceFigures.filter { it.danceType.equals(danceTypeName, ignoreCase = true) }

    // 1. Try explicit alias map first
    val aliasDbName = figureAliases[danceTypeName]?.get(lowerScraped)
    if (aliasDbName != null) {
        val matched = existingFiguresForStyle.find { it.name.lowercase(java.util.Locale.ROOT) == aliasDbName.lowercase(java.util.Locale.ROOT) }
        if (matched != null) return matched.name
    }

    // 2. Try normalized match
    val normScraped = normalizeName(scrapedName, danceTypeName)
    val exactMatch = existingFiguresForStyle.find { fig ->
        normalizeName(fig.name, danceTypeName) == normScraped
    }
    if (exactMatch != null) return exactMatch.name

    // 3. Try stripping parentheses and matching
    val strippedScraped = stripParentheses(scrapedName)
    val normStrippedScraped = normalizeName(strippedScraped, danceTypeName)
    val strippedMatch = existingFiguresForStyle.find { fig ->
        val strippedDb = stripParentheses(fig.name)
        normalizeName(strippedDb, danceTypeName) == normStrippedScraped
    }
    if (strippedMatch != null) return strippedMatch.name

    // 4. Try matching parenthetical content of DB name to normalized scraped name
    val parentheticalMatch = existingFiguresForStyle.find { fig ->
        val contentInsideParentheses = getParenthesesContent(fig.name)
        if (contentInsideParentheses != null) {
            normalizeName(contentInsideParentheses, danceTypeName) == normScraped
        } else {
            false
        }
    }
    if (parentheticalMatch != null) return parentheticalMatch.name

    return null
}

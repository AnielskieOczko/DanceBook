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
    csvFile.readLines().drop(1).forEach { line ->
        if (line.isNotBlank()) {
            val parts = parseCsvLine(line)
            if (parts.size > 2) {
                val name = parts[1]
                referenceNamesSet.add(name.trim().lowercase())
                val danceTypeId = parts[2]
                val danceName = danceTypeMap[danceTypeId] ?: "UNKNOWN"
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

                for (fig in parsedList) {
                    allFigures.add(fig)
                    val figName = fig["name"] as? String ?: "Unknown"
                    val danceType = fig["dance_type"] as? String ?: "Unknown"
                    val figUrl = fig["url"] as? String ?: ""
                    
                    if (referenceNamesSet.contains(figName.trim().lowercase())) {
                        matchedList.add("$figName ($danceType)")
                    } else {
                        unmatchedList.add("$figName ($danceType) -> URL: $figUrl")
                    }
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

package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureStep
import com.jankowski.rafal.dancebook.model.DanceFigureLink
import com.jankowski.rafal.dancebook.model.DanceFigureStepComment
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureStepRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.util.Locale

@Service
class SyllabusImporterService(
    private val danceFigureRepository: DanceFigureRepository,
    private val danceFigureStepRepository: DanceFigureStepRepository,
    private val danceTypeRepository: DanceTypeRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(SyllabusImporterService::class.java)

    data class ImportResult(
        val figuresUpdated: Int,
        val stepsCreated: Int,
        val skippedUnmatched: Int,
        val warnings: List<String>
    )

    data class ScrapedRecord(
        val url: String,
        val text: String?
    )

    @Transactional
    fun importFromDataset(relativeFilePath: String): ImportResult {
        val file = File(relativeFilePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Dataset file not found at: ${file.absolutePath}")
        }

        val records = try {
            objectMapper.readValue<List<ScrapedRecord>>(file)
        } catch (e: Exception) {
            log.error("Failed to parse dataset JSON file", e)
            throw IllegalArgumentException("Invalid JSON format: ${e.message}")
        }

        var figuresUpdated = 0
        var stepsCreated = 0
        var skippedUnmatched = 0
        val warnings = mutableListOf<String>()

        val danceTypes = danceTypeRepository.findAll()

        for (record in records) {
            val rawText = record.text ?: continue
            val url = record.url

            // 1. Detect Dance Type from URL
            val danceType = detectDanceType(url, danceTypes)
            if (danceType == null) {
                // If it's the index page, technique page, or exercises, skip silently
                val isIndexOrTechniqueOrExercise = url.endsWith("/international-style") ||
                    url.contains("technique") ||
                    url.contains("choreography") ||
                    url.contains("/exercises/") ||
                    danceTypes.any { type ->
                        val slug = type.name.lowercase(Locale.ROOT).replace(" ", "-")
                        url.endsWith("/$slug") || url.endsWith("/$slug/") || (slug == "cha-cha" && (url.endsWith("/cha-cha-cha") || url.endsWith("/cha-cha-cha/")))
                    }
                if (!isIndexOrTechniqueOrExercise) {
                    warnings.add("Could not detect dance type for URL: $url")
                }
                continue
            }

            // 2. Parse Figure Name from first line
            val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val rawName = parseFigureName(lines[0])
            if (rawName.isBlank() || rawName.lowercase() == "dance central") continue

            // 3. Parse Metadata and Steps
            val parsedData = parseTextContent(lines)

            val existingFigures = danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceType.id!!)
            val matchedFigure = findMatchingFigure(rawName, danceType.name, existingFigures)
            if (matchedFigure == null) {
                val isIndexOrTechnique = url.endsWith("/international-style") ||
                    url.contains("technique") ||
                    url.contains("choreography") ||
                    url.contains("routine-builder") ||
                    rawName.equals(danceType.name, ignoreCase = true) ||
                    rawName.equals("cha cha cha", ignoreCase = true) ||
                    url.endsWith("/${danceType.name.lowercase(Locale.ROOT).replace(" ", "-")}") ||
                    url.endsWith("/${danceType.name.lowercase(Locale.ROOT).replace(" ", "-")}/") ||
                    (danceType.name == "Cha Cha" && (url.endsWith("/cha-cha-cha") || url.endsWith("/cha-cha-cha/")))
                
                if (!isIndexOrTechnique) {
                    skippedUnmatched++
                    warnings.add("Unmatched figure: '$rawName' for style '${danceType.name}' (URL: $url)")
                }
                continue
            }

            // 5. Update Figure attributes
            matchedFigure.startingFootLeader = parsedData.startingFootLeader
            matchedFigure.endingFootLeader = parsedData.endingFootLeader
            matchedFigure.startingFootFollower = parsedData.startingFootFollower
            matchedFigure.endingFootFollower = parsedData.endingFootFollower
            matchedFigure.startingPosition = parsedData.startingPosition
            matchedFigure.endingPosition = parsedData.endingPosition
            matchedFigure.precedingFigureNames = parsedData.precedingFigures
            matchedFigure.followingFigureNames = parsedData.followingFigures

            val hasLink = matchedFigure.links.any { it.url == url }
            if (!hasLink) {
                val newLink = DanceFigureLink().apply {
                    this.danceFigure = matchedFigure
                    this.url = url
                    this.title = "DanceCentral Reference"
                    this.type = "CRAWLED"
                }
                matchedFigure.links.add(newLink)
            }

            danceFigureRepository.save(matchedFigure)

            // 6. Delete old steps and save new ones
            danceFigureStepRepository.deleteByDanceFigureId(matchedFigure.id!!)
            
            var stepNum = 1
            for (stepDto in parsedData.steps) {
                val step = DanceFigureStep().apply {
                    this.danceFigure = matchedFigure
                    this.stepNumber = stepNum++
                    this.timing = stepDto.timing
                    this.role = stepDto.role
                    this.foot = stepDto.foot
                    this.action = stepDto.action
                    this.footwork = stepDto.footwork
                    this.alignment = stepDto.alignment
                    this.amountOfTurn = stepDto.amountOfTurn
                }
                try {
                    danceFigureStepRepository.save(step)
                } catch (e: Exception) {
                    log.error("FAILED TO SAVE STEP: timing='${step.timing}' (len=${step.timing.length}), role='${step.role}' (len=${step.role.length}), foot='${step.foot}' (len=${step.foot.length}), footwork='${step.footwork}' (len=${step.footwork?.length}), action='${step.action}' (len=${step.action.length}), alignment='${step.alignment}' (len=${step.alignment?.length})", e)
                    throw e
                }
                stepsCreated++
            }

            figuresUpdated++
        }

        return ImportResult(
            figuresUpdated = figuresUpdated,
            stepsCreated = stepsCreated,
            skippedUnmatched = skippedUnmatched,
            warnings = warnings
        )
    }

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

    private fun detectDanceType(url: String, danceTypes: List<com.jankowski.rafal.dancebook.model.DanceType>): com.jankowski.rafal.dancebook.model.DanceType? {
        var lowerUrl = url.lowercase(Locale.ROOT)
        // Normalize common URL slugs to match database dance type names
        lowerUrl = lowerUrl.replace("slow-foxtrot", "foxtrot")
        lowerUrl = lowerUrl.replace("slow-waltz", "waltz")
        lowerUrl = lowerUrl.replace("cha-cha-cha", "cha-cha")
        
        return danceTypes.find { type ->
            val typeSlug = type.name.lowercase(Locale.ROOT).replace(" ", "-")
            lowerUrl.contains("/$typeSlug/") || lowerUrl.endsWith("/$typeSlug")
        }
    }

    private fun parseFigureName(firstLine: String): String {
        return firstLine.replace("Dance Central - ", "", ignoreCase = true).trim()
    }

    private fun findMatchingFigure(
        scrapedName: String,
        danceTypeName: String,
        existingFigures: List<DanceFigure>
    ): DanceFigure? {
        val lowerScraped = scrapedName.lowercase(Locale.ROOT).trim()

        // 1. Try explicit alias map first
        val aliasDbName = figureAliases[danceTypeName]?.get(lowerScraped)
        if (aliasDbName != null) {
            val matched = existingFigures.find { it.name.lowercase(Locale.ROOT) == aliasDbName.lowercase(Locale.ROOT) }
            if (matched != null) return matched
        }

        // 2. Try normalized match
        val normScraped = normalizeName(scrapedName, danceTypeName)
        val exactMatch = existingFigures.find { fig ->
            normalizeName(fig.name, danceTypeName) == normScraped
        }
        if (exactMatch != null) return exactMatch

        // 3. Try stripping parentheses and matching
        val strippedScraped = stripParentheses(scrapedName)
        val normStrippedScraped = normalizeName(strippedScraped, danceTypeName)
        val strippedMatch = existingFigures.find { fig ->
            val strippedDb = stripParentheses(fig.name)
            normalizeName(strippedDb, danceTypeName) == normStrippedScraped
        }
        if (strippedMatch != null) return strippedMatch

        // 4. Try matching parenthetical content of DB name to normalized scraped name
        val parentheticalMatch = existingFigures.find { fig ->
            val contentInsideParentheses = getParenthesesContent(fig.name)
            if (contentInsideParentheses != null) {
                normalizeName(contentInsideParentheses, danceTypeName) == normScraped
            } else {
                false
            }
        }
        if (parentheticalMatch != null) return parentheticalMatch

        return null
    }

    fun normalizeName(name: String, danceTypeName: String): String {
        var n = removeAccents(name).lowercase(Locale.ROOT)
        // Normalize "promenade position" to "pp"
        n = n.replace("promenade position", "pp")
        
        // Remove " and " and " & "
        n = n.replace(" and ", " ").replace(" & ", " ")

        // Remove dance style name prefix if present (e.g. "samba corta jaca" -> "corta jaca")
        val stylePrefix = danceTypeName.lowercase(Locale.ROOT) + " "
        if (n.startsWith(stylePrefix)) {
            n = n.substring(stylePrefix.length)
        }
        
        // Remove special punctuation, spaces, and formatting
        n = n.replace(Regex("[^a-z0-9]"), "")
        
        // Strip trailing "s" (simplistic singularization)
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

    private data class ParsedStepDto(
        val timing: String,
        val role: String,
        val foot: String,
        val action: String,
        val footwork: String?,
        val alignment: String?,
        val amountOfTurn: String?
    )

    private data class ParsedFigureData(
        val startingFootLeader: String?,
        val endingFootLeader: String?,
        val startingFootFollower: String?,
        val endingFootFollower: String?,
        val startingPosition: String?,
        val endingPosition: String?,
        val precedingFigures: List<String>,
        val followingFigures: List<String>,
        val steps: List<ParsedStepDto>
    )

    private fun parseTextContent(lines: List<String>): ParsedFigureData {
        var currentSection = "NONE"
        val steps = mutableListOf<ParsedStepDto>()
        val preceding = mutableListOf<String>()
        val following = mutableListOf<String>()

        var startingPosition: String? = null
        var endingPosition: String? = null

        // Step timing pattern matching e.g., "S:", "Q:", "1:", "1a2:", "a:"
        val stepRowRegex = Regex("^([1-9A-Za-z\\s&]+):\\s*(.*)")

        for (line in lines) {
            val lowerLine = line.lowercase(Locale.ROOT)
            
            // Section switches
            if (lowerLine == "leader" || lowerLine == "leader and follower") {
                currentSection = "LEADER"
                continue
            } else if (lowerLine == "follower") {
                currentSection = "FOLLOWER"
                continue
            } else if (lowerLine.startsWith("preceding figures")) {
                currentSection = "PRECEDING"
                continue
            } else if (lowerLine.startsWith("following figures")) {
                currentSection = "FOLLOWING"
                continue
            } else if (lowerLine.startsWith("ending position:")) {
                endingPosition = line.replace("Ending Position:", "", ignoreCase = true).trim()
                currentSection = "NONE"
                continue
            } else if (lowerLine.startsWith("note:") || lowerLine.startsWith("notes:") || lowerLine.startsWith("alternatives:") || lowerLine.startsWith("silver & gold:")) {
                currentSection = "NONE"
                continue
            }

            when (currentSection) {
                "LEADER", "FOLLOWER" -> {
                    // Check if it's the starting position line
                    if (lowerLine.startsWith("start in ")) {
                        if (currentSection == "LEADER") {
                            startingPosition = line.substring(9).substringBefore(".").trim()
                        }
                        continue
                    }

                    val match = stepRowRegex.matchEntire(line)
                    if (match != null) {
                        val timing = match.groupValues[1].trim()
                        if (isValidTiming(timing)) {
                            val content = match.groupValues[2].trim()

                            // Content has format: Action | Turn/Alignment | Footwork
                            val parts = content.split("|").map { it.trim() }
                            val action = parts[0]
                            val turnOrAlign = if (parts.size > 1 && parts[1].isNotEmpty() && parts[1] != "--") parts[1] else null
                            val footwork = if (parts.size > 2 && parts[2].isNotEmpty() && parts[2] != "--") parts[2] else null

                            // Detect foot weight (LF/RF)
                            val foot = when {
                                action.startsWith("LF", ignoreCase = true) || action.contains(" LF ", ignoreCase = true) -> "LF"
                                action.startsWith("RF", ignoreCase = true) || action.contains(" RF ", ignoreCase = true) -> "RF"
                                else -> "OTHER"
                            }

                            steps.add(ParsedStepDto(
                                timing = timing,
                                role = currentSection,
                                foot = foot,
                                action = action,
                                footwork = footwork,
                                alignment = turnOrAlign,
                                amountOfTurn = null
                            ))
                        }
                    }
                }
                "PRECEDING" -> {
                    if (line.isNotEmpty() && !line.startsWith("Preceding", ignoreCase = true)) {
                        preceding.add(line)
                    }
                }
                "FOLLOWING" -> {
                    if (line.isNotEmpty() && !line.startsWith("Following", ignoreCase = true)) {
                        following.add(line)
                    }
                }
            }
        }

        // Compute starting and ending feet
        val leaderSteps = steps.filter { it.role == "LEADER" }
        val startingFootLeader = leaderSteps.firstOrNull { it.foot != "OTHER" }?.foot
        val endingFootLeader = leaderSteps.lastOrNull { it.foot != "OTHER" }?.foot

        val followerSteps = steps.filter { it.role == "FOLLOWER" }
        val startingFootFollower = followerSteps.firstOrNull { it.foot != "OTHER" }?.foot
        val endingFootFollower = followerSteps.lastOrNull { it.foot != "OTHER" }?.foot

        return ParsedFigureData(
            startingFootLeader = startingFootLeader,
            endingFootLeader = endingFootLeader,
            startingFootFollower = startingFootFollower,
            endingFootFollower = endingFootFollower,
            startingPosition = startingPosition,
            endingPosition = endingPosition,
            precedingFigures = preceding,
            followingFigures = following,
            steps = steps
        )
    }

    private fun isValidTiming(timing: String): Boolean {
        if (timing.length > 20) return false
        val lower = timing.lowercase(Locale.ROOT)
        val forbiddenWords = listOf("position", "alternative", "figure", "change", "style", "start", "end", "direction", "step")
        if (forbiddenWords.any { lower.contains(it) }) return false

        // Timing should only contain digits, spaces, standard timing letters (s, q, a, e, &), and symbols like /, -, +
        val allowedChars = Regex("^[1-9sqae&/\\s\\-+]+$")
        return allowedChars.matches(lower)
    }

    @Transactional
    fun importAllAiParsedJson(): ImportResult {
        val parsedDir = File("docs/figures steps/parsed")
        if (!parsedDir.exists() || !parsedDir.isDirectory) {
            throw IllegalArgumentException("Parsed figures directory not found at: ${parsedDir.absolutePath}")
        }

        val files = parsedDir.listFiles { _, name -> name.matches(Regex("chunk_\\d+_parsed\\.json")) }
            ?: emptyArray()

        if (files.isEmpty()) {
            throw IllegalArgumentException("No parsed chunk JSON files found in: ${parsedDir.absolutePath}")
        }

        files.sortBy { file ->
            val num = file.name.substringAfter("chunk_").substringBefore("_parsed").toIntOrNull() ?: 0
            num
        }

        var totalFiguresUpdated = 0
        var totalStepsCreated = 0
        var totalSkippedUnmatched = 0
        val allWarnings = mutableListOf<String>()

        for (file in files) {
            log.info("Importing AI parsed figures from file: ${file.name}")
            try {
                val result = importFromAiParsedJson(file.absolutePath)
                totalFiguresUpdated += result.figuresUpdated
                totalStepsCreated += result.stepsCreated
                totalSkippedUnmatched += result.skippedUnmatched
                allWarnings.addAll(result.warnings.map { "[${file.name}] $it" })
            } catch (e: Exception) {
                log.error("Failed to import from file: ${file.name}", e)
                allWarnings.add("Error importing ${file.name}: ${e.message}")
            }
        }

        return ImportResult(
            figuresUpdated = totalFiguresUpdated,
            stepsCreated = totalStepsCreated,
            skippedUnmatched = totalSkippedUnmatched,
            warnings = allWarnings
        )
    }

    @Transactional
    fun importFromAiParsedJson(relativeFilePath: String): ImportResult {
        val file = File(relativeFilePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Parsed JSON file not found at: ${file.absolutePath}")
        }

        val records = try {
            objectMapper.readValue<List<AiParsedFigureDto>>(file)
        } catch (e: Exception) {
            log.error("Failed to parse AI structured JSON file: ${file.absolutePath}", e)
            throw IllegalArgumentException("Invalid JSON format in ${file.name}: ${e.message}")
        }

        var figuresUpdated = 0
        var stepsCreated = 0
        var skippedUnmatched = 0
        val warnings = mutableListOf<String>()

        val danceTypes = danceTypeRepository.findAll()

        for (record in records) {
            val recName = record.name ?: continue
            val recDanceType = record.dance_type ?: continue

            val dbDanceTypeName = mapDanceTypeJsonToDbName(recDanceType)
            val danceType = danceTypes.find { it.name.equals(dbDanceTypeName, ignoreCase = true) }
            if (danceType == null) {
                warnings.add("Could not find dance type in DB matching: $recDanceType (mapped to $dbDanceTypeName) for figure $recName")
                skippedUnmatched++
                continue
            }

            val existingFigures = danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceType.id!!)
            val matchedFigure = findMatchingFigure(recName, danceType.name, existingFigures)
            if (matchedFigure == null) {
                skippedUnmatched++
                warnings.add("Unmatched figure: '$recName' for style '${danceType.name}'")
                continue
            }

            // Update metadata
            matchedFigure.startingFootLeader = record.starting_foot_leader
            matchedFigure.endingFootLeader = record.ending_foot_leader
            matchedFigure.startingFootFollower = record.starting_foot_follower
            matchedFigure.endingFootFollower = record.ending_foot_follower
            matchedFigure.startingPosition = record.starting_position
            matchedFigure.endingPosition = record.ending_position
            matchedFigure.precedingFigureNames = parseListOrStringField(record.preceding_figure_names)
            matchedFigure.followingFigureNames = parseListOrStringField(record.following_figure_names)
            matchedFigure.notes = record.notes

            // Update links
            val urls = mutableListOf<String>()
            if (record.urls != null) {
                urls.addAll(record.urls)
            }
            if (record.url != null && !urls.contains(record.url)) {
                urls.add(record.url)
            }

            for (url in urls) {
                val hasLink = matchedFigure.links.any { it.url == url }
                if (!hasLink) {
                    val newLink = DanceFigureLink().apply {
                        this.danceFigure = matchedFigure
                        this.url = url
                        this.title = "DanceCentral Reference"
                        this.type = "CRAWLED"
                    }
                    matchedFigure.links.add(newLink)
                }
            }

            danceFigureRepository.save(matchedFigure)

            // Delete old steps and save new ones
            danceFigureStepRepository.deleteByDanceFigureId(matchedFigure.id!!)

            if (record.steps != null) {
                var stepNum = 1
                for (stepDto in record.steps) {
                    val step = DanceFigureStep().apply {
                        this.danceFigure = matchedFigure
                        val sn = stepDto.step_number
                        val parsedStepNum = when (sn) {
                            is Number -> sn.toInt()
                            is String -> sn.substringBefore("&").substringBefore(" ").trim().toIntOrNull() ?: stepNum
                            else -> stepNum
                        }
                        this.stepNumber = parsedStepNum
                        stepNum = parsedStepNum + 1
                        this.timing = stepDto.timing ?: ""
                        this.role = stepDto.role ?: ""
                        this.foot = stepDto.foot ?: ""
                        this.action = stepDto.action ?: ""
                        this.footwork = stepDto.footwork
                        this.alignment = stepDto.alignment
                        this.amountOfTurn = stepDto.amount_of_turn
                    }

                    if (stepDto.comments != null) {
                        var displayOrder = 1
                        for (commentText in stepDto.comments) {
                            if (commentText.isNotBlank()) {
                                val comment = DanceFigureStepComment().apply {
                                    this.danceFigureStep = step
                                    this.commentText = commentText
                                    this.displayOrder = displayOrder++
                                }
                                step.comments.add(comment)
                            }
                        }
                    }

                    try {
                        danceFigureStepRepository.save(step)
                    } catch (e: Exception) {
                        log.error("FAILED TO SAVE STEP: timing='${step.timing}', role='${step.role}', action='${step.action}'", e)
                        throw e
                    }
                    stepsCreated++
                }
            }

            figuresUpdated++
        }

        return ImportResult(
            figuresUpdated = figuresUpdated,
            stepsCreated = stepsCreated,
            skippedUnmatched = skippedUnmatched,
            warnings = warnings
        )
    }

    private fun parseListOrStringField(field: Any?): List<String> {
        if (field == null) return emptyList()
        if (field is List<*>) {
            return field.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        }
        if (field is String) {
            return field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
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

    data class AiParsedStepDto(
        val step_number: Any? = null,
        val timing: String? = null,
        val role: String? = null,
        val foot: String? = null,
        val action: String? = null,
        val footwork: String? = null,
        val alignment: String? = null,
        val amount_of_turn: String? = null,
        val comments: List<String>? = null
    )

    data class AiParsedFigureDto(
        val name: String? = null,
        val urls: List<String>? = null,
        val url: String? = null,
        val dance_type: String? = null,
        val level: String? = null,
        val starting_foot_leader: String? = null,
        val ending_foot_leader: String? = null,
        val starting_foot_follower: String? = null,
        val ending_foot_follower: String? = null,
        val starting_position: String? = null,
        val ending_position: String? = null,
        val preceding_figure_names: Any? = null,
        val following_figure_names: Any? = null,
        val notes: String? = null,
        val steps: List<AiParsedStepDto>? = null
    )
}


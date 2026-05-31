package com.jankowski.rafal.dancebook.scripts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jankowski.rafal.dancebook.service.SyllabusImporterService.AiParsedFigureDto
import java.io.File
import java.nio.file.Paths
import java.util.UUID

fun main() {
    val parsedDir = File("docs/figures steps/parsed")
    if (!parsedDir.exists() || !parsedDir.isDirectory) {
        System.err.println("Error: Parsed figures directory not found at docs/figures steps/parsed")
        System.exit(1)
    }

    val files = parsedDir.listFiles { _, name -> name.matches(Regex("chunk_\\d+_parsed\\.json")) }
        ?: emptyArray()

    if (files.isEmpty()) {
        System.err.println("No parsed chunk JSON files found in: ${parsedDir.absolutePath}")
        System.exit(1)
    }

    files.sortBy { file ->
        val num = file.name.substringAfter("chunk_").substringBefore("_parsed").toIntOrNull() ?: 0
        num
    }

    val objectMapper = jacksonObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val sqlBuilder = StringBuilder()

    sqlBuilder.append("-- Auto-generated seeding script for parsed figures metadata, steps, links, and comments\n")
    sqlBuilder.append("-- Source of truth: docs/figures steps/parsed/chunk_*_parsed.json\n\n")

    var figuresCount = 0
    var stepsCount = 0
    var linksCount = 0
    var commentsCount = 0

    for (file in files) {
        println("Processing JSON file for SQL generation: ${file.name}")
        val figures: List<AiParsedFigureDto> = try {
            objectMapper.readValue(file)
        } catch (e: Exception) {
            System.err.println("Error reading ${file.name}: ${e.message}")
            continue
        }

        for (record in figures) {
            val name = record.name ?: continue
            val jsonDanceType = record.dance_type ?: continue

            val dbDanceTypeName = mapDanceTypeJsonToDbName(jsonDanceType)
            val escapedName = escapeSql(name)
            val escapedDanceTypeName = escapeSql(dbDanceTypeName)

            sqlBuilder.append("-- Figure: $name ($dbDanceTypeName)\n")

            // 1. Insert or update the figure
            val precedingNamesJson = serializeList(record.preceding_figure_names)
            val followingNamesJson = serializeList(record.following_figure_names)

            sqlBuilder.append("""
                INSERT INTO dance_figure (id, name, dance_type_id, predefined, starting_foot_leader, ending_foot_leader, starting_foot_follower, ending_foot_follower, starting_position, ending_position, preceding_figure_names, following_figure_names, notes)
                VALUES (
                    gen_random_uuid(),
                    $escapedName,
                    (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName),
                    true,
                    ${escapeSql(record.starting_foot_leader)},
                    ${escapeSql(record.ending_foot_leader)},
                    ${escapeSql(record.starting_foot_follower)},
                    ${escapeSql(record.ending_foot_follower)},
                    ${escapeSql(record.starting_position)},
                    ${escapeSql(record.ending_position)},
                    ${escapeSql(precedingNamesJson)},
                    ${escapeSql(followingNamesJson)},
                    ${escapeSql(record.notes)}
                )
                ON CONFLICT (dance_type_id, name) DO UPDATE SET
                    predefined = EXCLUDED.predefined,
                    starting_foot_leader = EXCLUDED.starting_foot_leader,
                    ending_foot_leader = EXCLUDED.ending_foot_leader,
                    starting_foot_follower = EXCLUDED.starting_foot_follower,
                    ending_foot_follower = EXCLUDED.ending_foot_follower,
                    starting_position = EXCLUDED.starting_position,
                    ending_position = EXCLUDED.ending_position,
                    preceding_figure_names = EXCLUDED.preceding_figure_names,
                    following_figure_names = EXCLUDED.following_figure_names,
                    notes = EXCLUDED.notes;

            """.trimIndent())
            sqlBuilder.append("\n")

            // 2. Clear existing links and steps
            sqlBuilder.append("""
                DELETE FROM dance_figure_link WHERE dance_figure_id = (SELECT id FROM dance_figure WHERE name = $escapedName AND dance_type_id = (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName));
                DELETE FROM dance_figure_step WHERE dance_figure_id = (SELECT id FROM dance_figure WHERE name = $escapedName AND dance_type_id = (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName));

            """.trimIndent())
            sqlBuilder.append("\n")

            // 3. Insert links
            val urls = mutableListOf<String>()
            if (record.urls != null) {
                urls.addAll(record.urls)
            }
            if (record.url != null && !urls.contains(record.url)) {
                urls.add(record.url)
            }

            for (url in urls) {
                val linkKey = "${dbDanceTypeName}_${name}_$url"
                val linkUuid = UUID.nameUUIDFromBytes(linkKey.toByteArray()).toString()

                sqlBuilder.append("""
                    INSERT INTO dance_figure_link (id, dance_figure_id, url, title, type)
                    VALUES (
                        '$linkUuid',
                        (SELECT id FROM dance_figure WHERE name = $escapedName AND dance_type_id = (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName)),
                        ${escapeSql(url)},
                        'DanceCentral Reference',
                        'CRAWLED'
                    );
                """.trimIndent())
                sqlBuilder.append("\n")
                linksCount++
            }

            // 4. Insert steps and comments
            if (record.steps != null) {
                for ((stepIndex, stepDto) in record.steps.withIndex()) {
                    val sn = stepDto.step_number
                    val stepNumber = when (sn) {
                        is Number -> sn.toInt()
                        is String -> sn.substringBefore("&").substringBefore(" ").trim().toIntOrNull() ?: (stepIndex + 1)
                        else -> stepIndex + 1
                    }
                    val role = stepDto.role ?: ""
                    val stepKey = "${dbDanceTypeName}_${name}_${role}_${stepNumber}_$stepIndex"
                    val stepUuid = UUID.nameUUIDFromBytes(stepKey.toByteArray()).toString()

                    sqlBuilder.append("""
                        INSERT INTO dance_figure_step (id, dance_figure_id, step_number, timing, role, foot, action, footwork, alignment, amount_of_turn)
                        VALUES (
                            '$stepUuid',
                            (SELECT id FROM dance_figure WHERE name = $escapedName AND dance_type_id = (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName)),
                            $stepNumber,
                            ${escapeSql(stepDto.timing)},
                            ${escapeSql(role)},
                            ${escapeSql(stepDto.foot)},
                            ${escapeSql(stepDto.action)},
                            ${escapeSql(stepDto.footwork)},
                            ${escapeSql(stepDto.alignment)},
                            ${escapeSql(stepDto.amount_of_turn)}
                        );
                    """.trimIndent())
                    sqlBuilder.append("\n")
                    stepsCount++

                    if (stepDto.comments != null) {
                        for ((commentIndex, commentText) in stepDto.comments.withIndex()) {
                            if (commentText.isNotBlank()) {
                                val commentKey = "${stepKey}_comment_$commentIndex"
                                val commentUuid = UUID.nameUUIDFromBytes(commentKey.toByteArray()).toString()

                                sqlBuilder.append("""
                                    INSERT INTO dance_figure_step_comment (id, dance_figure_step_id, comment_text, display_order)
                                    VALUES (
                                        '$commentUuid',
                                        '$stepUuid',
                                        ${escapeSql(commentText)},
                                        ${commentIndex + 1}
                                    );
                                """.trimIndent())
                                sqlBuilder.append("\n")
                                commentsCount++
                            }
                        }
                    }
                }
            }

            sqlBuilder.append("\n")
            figuresCount++
        }
    }

    val migrationFile = Paths.get("src/main/resources/db/migration/V23__seed_figures_details.sql").toFile()
    migrationFile.parentFile.mkdirs()
    migrationFile.writeText(sqlBuilder.toString())

    println("SQL seed script successfully generated!")
    println("Output file: ${migrationFile.absolutePath}")
    println("Summary of generated records:")
    println("  - Figures: $figuresCount")
    println("  - Links: $linksCount")
    println("  - Steps: $stepsCount")
    println("  - Comments: $commentsCount")
}

private fun escapeSql(str: String?): String {
    if (str == null) return "NULL"
    return "'" + str.replace("'", "''") + "'"
}

private fun serializeList(field: Any?): String? {
    if (field == null) return null
    if (field is List<*>) {
        val cleanList = field.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        if (cleanList.isEmpty()) return null
        return jacksonObjectMapper().writeValueAsString(cleanList)
    }
    if (field is String) {
        val cleanList = field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanList.isEmpty()) return null
        return jacksonObjectMapper().writeValueAsString(cleanList)
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

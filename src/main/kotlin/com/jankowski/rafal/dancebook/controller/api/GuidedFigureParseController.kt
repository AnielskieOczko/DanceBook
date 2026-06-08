package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.dto.GuidedParseJsonRequest
import com.jankowski.rafal.dancebook.dto.GuidedParseResult
import com.jankowski.rafal.dancebook.dto.GuidedParseUrlRequest
import com.jankowski.rafal.dancebook.service.GuidedFigureParseService
import com.jankowski.rafal.dancebook.service.LlmProviderRouter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dance-figures/guided-parse")
class GuidedFigureParseController(
    private val guidedFigureParseService: GuidedFigureParseService,
    private val llmProviderRouter: LlmProviderRouter
) {

    @PostMapping("/json")
    fun parseFromJson(@RequestBody request: GuidedParseJsonRequest): ResponseEntity<GuidedParseResult> {
        val result = guidedFigureParseService.parseFromJson(request.json)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/url")
    fun parseFromUrl(@RequestBody request: GuidedParseUrlRequest): ResponseEntity<GuidedParseResult> {
        val result = guidedFigureParseService.parseFromUrl(
            url = request.url,
            provider = request.provider,
            model = request.model,
            danceTypeId = request.danceTypeId,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            providerSettings = request.providerSettings
        )
        return ResponseEntity.ok(result)
    }

    @GetMapping("/models")
    fun getModels(): ResponseEntity<Map<String, List<String>>> {
        val models = llmProviderRouter.getAllModels()
        return ResponseEntity.ok(models)
    }

    @GetMapping("/schema")
    fun getExpectedJsonSchema(): ResponseEntity<String> {
        val schema = """
{
  "name": "Standardized Figure Name",
  "dance_type": "SAMBA | CHA_CHA_CHA | RUMBA | JIVE | WALTZ | TANGO | VIENNESE_WALTZ | SLOW_FOXTROT",
  "level": "Bronze | Silver | Gold | Newcomer",
  "starting_foot_leader": "LF | RF",
  "ending_foot_leader": "LF | RF",
  "starting_foot_follower": "LF | RF",
  "ending_foot_follower": "LF | RF",
  "starting_position": "e.g., Closed Position",
  "ending_position": "e.g., Fan Position",
  "preceding_figure_names": "Comma separated list or JSON array of names",
  "following_figure_names": "Comma separated list or JSON array of names",
  "notes": "General notes, alternative endings, etc.",
  "steps": [
    {
      "step_number": 1,
      "timing": "e.g., 1, a, 2, S, Q",
      "role": "LEADER | FOLLOWER",
      "foot": "LF | RF | TOGETHER",
      "action": "Description of the movement",
      "footwork": "e.g., HF, BF, T",
      "alignment": "e.g., Facing Wall",
      "amount_of_turn": "e.g., 1/4 to R",
      "comments": [
        "First detail comment.",
        "Second detail comment."
      ]
    }
  ]
}
        """.trimIndent()
        return ResponseEntity.ok(schema)
    }
}

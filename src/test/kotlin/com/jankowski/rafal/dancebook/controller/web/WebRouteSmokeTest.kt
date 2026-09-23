package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.stereotype.Controller
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

/**
 * Smoke test exercising every GET route served by web controllers in the application.
 *
 * Catches templates that parse at startup but throw TemplateProcessingException at render time
 * (e.g. Issue #103 where restricted SpEL expression mode rejected "new " in subtitle literals).
 *
 * Routes are dynamically discovered from [RequestMappingHandlerMapping] across all @Controller
 * classes (excluding @RestController APIs), ensuring that newly added GET routes cannot silently
 * escape coverage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class WebRouteSmokeTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var commentRepository: CommentRepository
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var customListRepository: CustomListRepository
    @Autowired private lateinit var choreographyRepository: ChoreographyRepository

    @MockBean private lateinit var googleDriveService: GoogleDriveService
    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var fixtures: Fixtures

    data class Fixtures(
        val adminUser: AppUser,
        val category: DanceCategory,
        val danceType: DanceType,
        val figure: DanceFigure,
        val material: Material,
        val comment: Comment,
        val calendar: TrainingCalendar,
        val event: TrainingEvent,
        val customList: CustomList,
        val choreography: Choreography
    )

    data class WebRoute(
        val controllerClass: Class<*>,
        val methodName: String,
        val pattern: String,
        val isResponseBody: Boolean
    )

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())
        ensureFixtures()
    }

    private fun ensureFixtures() {
        if (::fixtures.isInitialized) return

        val admin = appUserRepository.findByUsername("smoke-admin") ?: appUserRepository.save(AppUser().apply {
            username = "smoke-admin"
            email = "smoke-admin@example.com"
            displayName = "Smoke Admin"
            password = "smoke-password"
            role = Role.ADMIN
        })

        val category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Smoke Category ${UUID.randomUUID()}"
            predefined = true
        })

        val danceType = danceTypeRepository.save(DanceType().apply {
            name = "Smoke Waltz ${UUID.randomUUID()}"
            this.category = category
        })

        val figure = DanceFigure().apply {
            name = "Smoke Figure ${UUID.randomUUID()}"
            this.danceType = danceType
            danceClass = DanceClass.D
            predefined = false
        }
        val defaultStepSet = DanceFigureStepSet().apply {
            name = "Default Set"
            isDefault = true
            this.danceFigure = figure
        }
        val step1 = DanceFigureStep().apply {
            this.danceFigureStepSet = defaultStepSet
            stepNumber = 1
            timing = "1"
            role = "LEADER"
            foot = "RF"
            action = "Forward"
        }
        val step2 = DanceFigureStep().apply {
            this.danceFigureStepSet = defaultStepSet
            stepNumber = 1
            timing = "1"
            role = "FOLLOWER"
            foot = "LF"
            action = "Back"
        }
        defaultStepSet.steps = mutableListOf(step1, step2)
        figure.stepSets.add(defaultStepSet)
        val savedFigure = danceFigureRepository.save(figure)

        val material = Material().apply {
            name = "Smoke Material ${UUID.randomUUID()}"
            this.danceType = danceType
            driveFileId = "smoke-drive-file"
        }
        val figureOccurrence = Figure().apply {
            this.material = material
            this.danceFigure = savedFigure
            startTime = 0
            endTime = 10
        }
        material.figures.add(figureOccurrence)
        val savedMaterial = materialRepository.save(material)

        val comment = commentRepository.save(Comment().apply {
            content = "Smoke test comment"
            author = admin
            this.material = savedMaterial
        })

        val calendar = trainingCalendarRepository.findByIsDefaultTrue()
            ?: trainingCalendarRepository.save(TrainingCalendar().apply {
                googleCalendarId = "smoke-cal-${UUID.randomUUID()}"
                displayName = "Smoke Calendar"
                isDefault = true
                enabled = true
            })

        val event = TrainingEvent().apply {
            title = "Smoke Event"
            startTime = LocalDateTime.now().minusHours(1)
            endTime = LocalDateTime.now().plusHours(1)
            eventType = TrainingEventType.TRAINING
            attendanceStatus = AttendanceStatus.ATTENDED
            this.calendar = calendar
            createdBy = admin
        }
        val segment = TrainingEventSegment().apply {
            this.trainingEvent = event
            this.danceCategory = category
            durationMinutes = 60
            sortOrder = 0
        }
        event.segments.add(segment)
        val savedEvent = trainingEventRepository.save(event)

        val customList = customListRepository.save(CustomList().apply {
            name = "Smoke List ${UUID.randomUUID()}"
            owner = admin
            isPublic = true
        })

        val choreography = Choreography().apply {
            name = "Smoke Choreography ${UUID.randomUUID()}"
            this.danceType = danceType
            owner = admin
            isPublic = true
        }
        val entry = ChoreographyEntry().apply {
            this.choreography = choreography
            entryType = EntryType.FIGURE
            this.danceFigure = savedFigure
            sortOrder = 1
        }
        choreography.entries.add(entry)
        val savedChoreography = choreographyRepository.save(choreography)

        fixtures = Fixtures(
            adminUser = admin,
            category = category,
            danceType = danceType,
            figure = savedFigure,
            material = savedMaterial,
            comment = comment,
            calendar = calendar,
            event = savedEvent,
            customList = customList,
            choreography = savedChoreography
        )
    }

    private fun discoverWebGetRoutes(): List<WebRoute> {
        val routes = mutableListOf<WebRoute>()
        for ((info, handlerMethod) in handlerMapping.handlerMethods) {
            val beanType = handlerMethod.beanType
            // Must be within com.jankowski.rafal.dancebook.controller
            if (!beanType.packageName.startsWith("com.jankowski.rafal.dancebook.controller")) continue
            // Must be @Controller and NOT @RestController
            if (!beanType.isAnnotationPresent(Controller::class.java)) continue
            if (beanType.isAnnotationPresent(RestController::class.java)) continue

            // Exclude test harness controllers defined in test sources
            val codePath = beanType.protectionDomain?.codeSource?.location?.path ?: ""
            if (codePath.contains("/test/") || codePath.contains("classes/kotlin/test")) continue
            if (beanType.simpleName.contains("Harness") || beanType.simpleName.contains("Test")) continue

            val methods = info.methodsCondition.methods
            val handlesGet = methods.isEmpty() || methods.contains(RequestMethod.GET)
            if (!handlesGet) continue

            val isResponseBody = handlerMethod.hasMethodAnnotation(ResponseBody::class.java) ||
                beanType.isAnnotationPresent(ResponseBody::class.java)

            for (pattern in info.patternValues) {
                routes.add(
                    WebRoute(
                        controllerClass = beanType,
                        methodName = handlerMethod.method.name,
                        pattern = pattern,
                        isResponseBody = isResponseBody
                    )
                )
            }
        }
        return routes.sortedWith(compareBy({ it.controllerClass.simpleName }, { it.pattern }))
    }

    private fun resolveUri(route: WebRoute): String {
        var uri = route.pattern

        // Named multi-variable paths (e.g. CommentController)
        if (uri.contains("{materialId}")) {
            uri = uri.replace("{materialId}", fixtures.material.id.toString())
        }
        if (uri.contains("{commentId}")) {
            uri = uri.replace("{commentId}", fixtures.comment.id.toString())
        }

        // Generic {id} paths resolved by controller entity type
        if (uri.contains("{id}")) {
            val entityId = when (route.controllerClass) {
                TrainingEventWebController::class.java -> fixtures.event.id
                DanceFigureWebController::class.java -> fixtures.figure.id
                DanceTypeWebController::class.java -> fixtures.danceType.id
                AdminController::class.java -> fixtures.adminUser.id
                MaterialWebController::class.java -> fixtures.material.id
                AdminCalendarController::class.java -> fixtures.calendar.id
                DanceCategoryWebController::class.java -> fixtures.category.id
                CustomListWebController::class.java -> fixtures.customList.id
                ChoreographyWebController::class.java -> fixtures.choreography.id
                else -> throw IllegalArgumentException(
                    "Unmapped {id} path variable for controller ${route.controllerClass.simpleName} on pattern '${route.pattern}'"
                )
            }
            uri = uri.replace("{id}", entityId.toString())
        }

        // Specific required query parameters
        if (uri == "/dance-figures/api") {
            uri += "?danceTypeId=${fixtures.danceType.id}"
        } else if (uri.endsWith("/figures-search")) {
            uri += "?query=turn"
        } else if (uri == "/training-events/quick-create") {
            // An all-day selection (midnight-to-midnight, >= 24h) exercises the evening slot rewrite branch
            uri += "?start=2026-03-21T00:00:00&end=2026-03-22T00:00:00"
        }

        // Safeguard: no unresolved path variables allowed
        if (uri.contains("{") || uri.contains("}")) {
            throw IllegalStateException(
                "Route pattern '${route.pattern}' on ${route.controllerClass.simpleName}.${route.methodName} " +
                    "contains unresolved path variables in '$uri'. Add fixture resolution to WebRouteSmokeTest."
            )
        }

        return uri
    }

    @Test
    fun `every web controller GET mapping is discovered and covered`() {
        val routes = discoverWebGetRoutes()
        // Approximately 55 routes across 16 controllers, plus HomeController at root
        assertTrue(
            routes.size >= 55,
            "Expected at least 55 web controller GET routes, but discovered ${routes.size}. Routes found:\n" +
                routes.joinToString("\n") { "  ${it.controllerClass.simpleName}.${it.methodName}: ${it.pattern}" }
        )

        // Verify that every route can be resolved without error
        for (route in routes) {
            val resolved = resolveUri(route)
            assertTrue(
                resolved.isNotBlank(),
                "Resolved URI for ${route.controllerClass.simpleName}.${route.methodName} (${route.pattern}) must not be blank"
            )
        }
    }

    @TestFactory
    fun `every web controller GET route responds successfully without render errors`(): List<DynamicTest> {
        ensureFixtures()
        val routes = discoverWebGetRoutes()

        return routes.map { route ->
            val uri = resolveUri(route)
            val testName = "GET ${route.pattern} -> $uri"

            DynamicTest.dynamicTest(testName) {
                val mvcResult = try {
                    mockMvc.perform(
                        get(uri)
                            .with(csrf())
                            .with(user(fixtures.adminUser.username).roles("ADMIN", "USER"))
                    ).andReturn()
                } catch (e: Throwable) {
                    throw AssertionError("Route GET ${route.pattern} ($uri) threw exception during execution: ${e.message}", e)
                }

                val status = mvcResult.response.status
                val resolvedException = mvcResult.resolvedException

                // Every route must reach a rendering outcome (HTTP 200 OK).
                // Any route that genuinely cannot render one must be an explicit, named exception
                // in this test with a stated reason, rather than silently passing on a 4xx.
                if (status != 200 || resolvedException != null) {
                    val errorDetails = resolvedException?.message
                        ?: mvcResult.response.errorMessage
                        ?: "HTTP $status"
                    throw AssertionError(
                        "Route GET ${route.pattern} ($uri) failed to reach a rendering outcome (status HTTP $status): $errorDetails",
                        resolvedException
                    )
                }

                // Assert no rendered form posts the same field name more than once (Issue #121)
                val html = mvcResult.response.contentAsString
                val duplicateErrors = findDuplicateFormFieldErrors(html, uri)
                if (duplicateErrors.isNotEmpty()) {
                    throw AssertionError(
                        "Route GET ${route.pattern} ($uri) rendered form with duplicate field names:\n" +
                            duplicateErrors.joinToString("\n")
                    )
                }
            }
        }
    }

    private fun findDuplicateFormFieldErrors(html: String, uri: String): List<String> {
        val formRegex = Regex("""<form\b[^>]*>(.*?)</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val elementRegex = Regex("""<(input|select|textarea)\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""\bname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val typeRegex = Regex("""\btype\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val disabledRegex = Regex("""\bdisabled\b""", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

        val errors = mutableListOf<String>()

        for ((formIndex, formMatch) in formRegex.findAll(html).withIndex()) {
            val formContent = formMatch.groupValues[1]
            val fieldNames = mutableListOf<String>()
            val checkboxNamesAndValues = mutableSetOf<Pair<String, String>>()

            for (elMatch in elementRegex.findAll(formContent)) {
                val tag = elMatch.groupValues[1].lowercase()
                val attrs = elMatch.groupValues[2]

                // Disabled elements are not submittable and do not post form state
                if (disabledRegex.containsMatchIn(attrs)) continue

                val type = typeRegex.find(attrs)?.groupValues?.get(1)?.lowercase() ?: if (tag == "input") "text" else ""

                // Submit/button elements do not post form state; radio buttons share name by design
                if (type == "submit" || type == "button" || type == "reset" || type == "radio") continue

                val name = nameRegex.find(attrs)?.groupValues?.get(1)?.trim()
                if (name.isNullOrEmpty() || name == "_csrf") continue

                if (type == "checkbox") {
                    val value = valueRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    // Multiple checkboxes sharing the same name must have distinct values (multi-select)
                    if (!checkboxNamesAndValues.add(name to value)) {
                        fieldNames.add(name)
                    }
                } else {
                    fieldNames.add(name)
                }
            }

            val duplicates = fieldNames.groupingBy { it }.eachCount().filter { it.value > 1 }
            if (duplicates.isNotEmpty()) {
                errors.add("Form #$formIndex in $uri has duplicate field names: $duplicates")
            }
        }

        return errors
    }
}

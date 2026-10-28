package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureStep
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.openhtmltopdf.extend.FSSupplier
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

data class PdfCommentItem(
    val author: String,
    val date: String,
    val contentHtml: String
)

/** Everything one note's section of a PDF needs, so the single and the list export fill the same template. */
data class PdfNoteItem(
    val material: Material,
    val figures: List<Figure>,
    val comments: List<PdfCommentItem>,
    val categoryImageBase64: String?,
    val ownerName: String,
    val createdDateFormatted: String?,
    val updatedDateFormatted: String?
)

/** Everything one figure's section of a PDF needs. */
data class PdfFigureItem(
    val danceFigure: DanceFigure,
    val leaderSteps: List<DanceFigureStep>,
    val followerSteps: List<DanceFigureStep>,
    val renderedNotes: String?,
    val categoryImageBase64: String?,
    val creatorName: String?,
    val createdDateFormatted: String?
)

/** One line of the table of contents: [anchor] is the id of the item's section. */
data class PdfTocEntry(val anchor: String, val number: Int, val title: String, val detail: String?)

@Service
class PdfExportServiceImpl(
    private val templateEngine: TemplateEngine,
    private val commentService: CommentService,
    private val materialService: MaterialService,
    private val richTextService: RichTextService,
    private val applicationContext: ApplicationContext,
    @Value("\${app.storage.upload-dir:uploads}")
    private val uploadDir: String
) : PdfExportService {

    companion object {
        private val log = LoggerFactory.getLogger(PdfExportServiceImpl::class.java)
    }

    private fun dateTimeFormatter(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)

    private fun dateFormatter(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    private fun newContext(locale: Locale): Context = Context(locale).apply {
        setVariable(
            ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
            ThymeleafEvaluationContext(applicationContext, null)
        )
    }

    /** Category images are the same few files for every item of a list, so a list export loads each one once. */
    private fun cachedImage(cache: MutableMap<String, String?>, filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        return if (cache.containsKey(filename)) cache[filename] else loadAndScaleImageBase64(filename).also { cache[filename] = it }
    }

    private fun buildNoteItem(material: Material, locale: Locale, images: MutableMap<String, String?>): PdfNoteItem {
        val figures = material.id?.let { materialService.findFiguresByMaterial(it) } ?: emptyList()
        val rawComments = material.id?.let { commentService.getCommentsForMaterial(it) } ?: emptyList()
        val dateTimeFormatter = dateTimeFormatter(locale)
        val dateFormatter = dateFormatter(locale)

        val comments = rawComments.map { comment ->
            PdfCommentItem(
                author = comment.author?.displayName ?: comment.author?.username ?: "",
                date = comment.createdAt.format(dateTimeFormatter),
                contentHtml = richTextService.render(comment.content) ?: ""
            )
        }

        return PdfNoteItem(
            material = material,
            figures = figures,
            comments = comments,
            categoryImageBase64 = cachedImage(images, material.danceType?.category?.imageFilename),
            ownerName = material.owner?.displayName ?: material.owner?.username ?: "",
            createdDateFormatted = material.createdAt.format(dateFormatter),
            updatedDateFormatted = material.updatedAt?.format(dateFormatter)
        )
    }

    private fun buildFigureItem(figure: DanceFigure, locale: Locale, images: MutableMap<String, String?>): PdfFigureItem {
        return PdfFigureItem(
            danceFigure = figure,
            leaderSteps = figure.getLeaderSteps(),
            followerSteps = figure.getFollowerSteps(),
            renderedNotes = richTextService.render(figure.notes),
            categoryImageBase64 = cachedImage(images, figure.danceType?.category?.imageFilename),
            creatorName = figure.createdBy?.displayName ?: figure.createdBy?.username,
            createdDateFormatted = figure.createdAt?.format(dateFormatter(locale))
        )
    }

    @Transactional(readOnly = true)
    override fun exportMaterialPdf(material: Material, locale: Locale): ByteArray {
        val item = buildNoteItem(material, locale, HashMap())

        val context = newContext(locale).apply {
            setVariable("material", item.material)
            setVariable("figures", item.figures)
            setVariable("comments", item.comments)
            setVariable("renderedDescription", richTextService.render(material.description))
            setVariable("categoryImageBase64", item.categoryImageBase64)
            setVariable("ownerName", item.ownerName)
            setVariable("createdDateFormatted", item.createdDateFormatted)
            setVariable("updatedDateFormatted", item.updatedDateFormatted)
            setVariable("exportDateFormatted", LocalDateTime.now().format(dateTimeFormatter(locale)))
        }

        val html = templateEngine.process("pdf/note", context)
        return renderHtmlToPdf(html)
    }

    @Transactional(readOnly = true)
    override fun exportDanceFigurePdf(danceFigure: DanceFigure, locale: Locale): ByteArray {
        val item = buildFigureItem(danceFigure, locale, HashMap())

        val context = newContext(locale).apply {
            setVariable("danceFigure", item.danceFigure)
            setVariable("leaderSteps", item.leaderSteps)
            setVariable("followerSteps", item.followerSteps)
            setVariable("renderedNotes", item.renderedNotes)
            setVariable("categoryImageBase64", item.categoryImageBase64)
            setVariable("creatorName", item.creatorName)
            setVariable("createdDateFormatted", item.createdDateFormatted)
            setVariable("exportDateFormatted", LocalDateTime.now().format(dateTimeFormatter(locale)))
        }

        val html = templateEngine.process("pdf/figure", context)
        return renderHtmlToPdf(html)
    }

    @Transactional(readOnly = true)
    override fun exportMaterialListPdf(materials: List<Material>, cover: PdfListCover, locale: Locale): ByteArray {
        require(materials.size <= PdfExportService.MAX_ITEMS_PER_PDF) {
            "A PDF holds at most ${PdfExportService.MAX_ITEMS_PER_PDF} items, got ${materials.size}"
        }
        val images = HashMap<String, String?>()
        val items = materials.map { buildNoteItem(it, locale, images) }
        val toc = items.mapIndexed { index, item ->
            PdfTocEntry("item-$index", index + 1, item.material.name, item.material.danceType?.name)
        }

        val context = newContext(locale).apply {
            setVariable("cover", cover)
            setVariable("coverTitleKey", "pdf.list.notes.title")
            setVariable("items", items)
            setVariable("toc", toc)
            setVariable("exportDateFormatted", LocalDateTime.now().format(dateTimeFormatter(locale)))
        }
        return renderHtmlToPdf(templateEngine.process("pdf/note-list", context))
    }

    @Transactional(readOnly = true)
    override fun exportDanceFigureListPdf(figures: List<DanceFigure>, cover: PdfListCover, locale: Locale): ByteArray {
        require(figures.size <= PdfExportService.MAX_ITEMS_PER_PDF) {
            "A PDF holds at most ${PdfExportService.MAX_ITEMS_PER_PDF} items, got ${figures.size}"
        }
        val images = HashMap<String, String?>()
        val items = figures.map { buildFigureItem(it, locale, images) }
        val toc = items.mapIndexed { index, item ->
            PdfTocEntry("item-$index", index + 1, item.danceFigure.name, item.danceFigure.danceType?.name)
        }

        val context = newContext(locale).apply {
            setVariable("cover", cover)
            setVariable("coverTitleKey", "pdf.list.figures.title")
            setVariable("items", items)
            setVariable("toc", toc)
            setVariable("exportDateFormatted", LocalDateTime.now().format(dateTimeFormatter(locale)))
        }
        return renderHtmlToPdf(templateEngine.process("pdf/figure-list", context))
    }

    override fun renderHtmlToPdf(html: String): ByteArray {
        val doc = Jsoup.parse(html, "UTF-8")
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        val xhtml = doc.html()

        val outputStream = ByteArrayOutputStream()
        val builder = PdfRendererBuilder()

        val regularSupplier = FSSupplier<InputStream> {
            javaClass.getResourceAsStream("/fonts/Inter-Regular.ttf")
                ?: throw IllegalStateException("Inter-Regular.ttf not found on classpath")
        }
        val boldSupplier = FSSupplier<InputStream> {
            javaClass.getResourceAsStream("/fonts/Inter-Bold.ttf")
                ?: throw IllegalStateException("Inter-Bold.ttf not found on classpath")
        }

        builder.useFont(regularSupplier, "Inter", 400, BaseRendererBuilder.FontStyle.NORMAL, true)
        builder.useFont(boldSupplier, "Inter", 700, BaseRendererBuilder.FontStyle.NORMAL, true)

        builder.withHtmlContent(xhtml, null)
        builder.toStream(outputStream)
        builder.run()

        return outputStream.toByteArray()
    }

    fun loadAndScaleImageBase64(imageFilename: String?): String? {
        if (imageFilename.isNullOrBlank()) return null
        return try {
            val uploadPath = Paths.get(uploadDir, "categories", imageFilename)
            val inputStream: InputStream? = if (Files.exists(uploadPath)) {
                Files.newInputStream(uploadPath)
            } else {
                javaClass.getResourceAsStream("/static/images/categories/$imageFilename")
                    ?: javaClass.getResourceAsStream("/static/images/$imageFilename")
            }

            if (inputStream == null) return null

            inputStream.use { stream ->
                val originalImage = ImageIO.read(stream) ?: return null
                val maxDim = 300
                val width = originalImage.width
                val height = originalImage.height
                val scale = minOf(maxDim.toDouble() / width, maxDim.toDouble() / height, 1.0)
                val targetW = (width * scale).toInt().coerceAtLeast(1)
                val targetH = (height * scale).toInt().coerceAtLeast(1)

                val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
                val g2d = scaled.createGraphics()
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g2d.drawImage(originalImage, 0, 0, targetW, targetH, null)
                } finally {
                    g2d.dispose()
                }

                val baos = ByteArrayOutputStream()
                ImageIO.write(scaled, "png", baos)
                "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray())
            }
        } catch (e: Exception) {
            log.warn("Failed to load or scale image {}: {}", imageFilename, e.message)
            null
        }
    }
}

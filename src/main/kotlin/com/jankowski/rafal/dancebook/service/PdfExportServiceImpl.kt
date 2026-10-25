package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
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

    @Transactional(readOnly = true)
    override fun exportMaterialPdf(material: Material, locale: Locale): ByteArray {
        val figures = material.id?.let { materialService.findFiguresByMaterial(it) } ?: emptyList()
        val rawComments = material.id?.let { commentService.getCommentsForMaterial(it) } ?: emptyList()

        val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

        val comments = rawComments.map { comment ->
            PdfCommentItem(
                author = comment.author?.displayName ?: comment.author?.username ?: "",
                date = comment.createdAt.format(dateTimeFormatter),
                contentHtml = richTextService.render(comment.content) ?: ""
            )
        }

        val renderedDescription = richTextService.render(material.description)
        val categoryImageBase64 = loadAndScaleImageBase64(material.danceType?.category?.imageFilename)

        val context = Context(locale).apply {
            setVariable(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                ThymeleafEvaluationContext(applicationContext, null)
            )
            setVariable("material", material)
            setVariable("figures", figures)
            setVariable("comments", comments)
            setVariable("renderedDescription", renderedDescription)
            setVariable("categoryImageBase64", categoryImageBase64)
            setVariable("ownerName", material.owner?.displayName ?: material.owner?.username ?: "")
            setVariable("createdDateFormatted", material.createdAt.format(dateFormatter))
            setVariable("updatedDateFormatted", material.updatedAt?.format(dateFormatter))
            setVariable("exportDateFormatted", LocalDateTime.now().format(dateTimeFormatter))
        }

        val html = templateEngine.process("pdf/note", context)
        return renderHtmlToPdf(html)
    }

    @Transactional(readOnly = true)
    override fun exportDanceFigurePdf(danceFigure: DanceFigure, locale: Locale): ByteArray {
        val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

        val renderedNotes = richTextService.render(danceFigure.notes)
        val leaderSteps = danceFigure.getLeaderSteps()
        val followerSteps = danceFigure.getFollowerSteps()
        val categoryImageBase64 = loadAndScaleImageBase64(danceFigure.danceType?.category?.imageFilename)

        val context = Context(locale).apply {
            setVariable(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                ThymeleafEvaluationContext(applicationContext, null)
            )
            setVariable("danceFigure", danceFigure)
            setVariable("leaderSteps", leaderSteps)
            setVariable("followerSteps", followerSteps)
            setVariable("renderedNotes", renderedNotes)
            setVariable("categoryImageBase64", categoryImageBase64)
            setVariable("creatorName", danceFigure.createdBy?.displayName ?: danceFigure.createdBy?.username)
            setVariable("createdDateFormatted", danceFigure.createdAt?.format(dateFormatter))
            setVariable("exportDateFormatted", LocalDateTime.now().format(dateTimeFormatter))
        }

        val html = templateEngine.process("pdf/figure", context)
        return renderHtmlToPdf(html)
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

package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.service.RichTextService
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [RichTextLengthValidator::class])
annotation class RichTextLength(
    val max: Int = 2000,
    val maxRaw: Int = 20000,
    val message: String = "Length cannot exceed {max} characters",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class RichTextLengthValidator(
    private val richTextService: RichTextService
) : ConstraintValidator<RichTextLength, String?> {

    private var max: Int = 2000
    private var maxRaw: Int = 20000

    override fun initialize(annotation: RichTextLength) {
        this.max = annotation.max
        this.maxRaw = annotation.maxRaw
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true

        if (value.length > maxRaw) {
            context.disableDefaultConstraintViolation()
            context.buildConstraintViolationWithTemplate("Raw content exceeds storage limit of $maxRaw characters")
                .addConstraintViolation()
            return false
        }

        val typedLength = richTextService.userTextLength(value)
        return typedLength <= max
    }
}

package com.jankowski.rafal.dancebook.service

/**
 * Deleting a figure was refused because notes or choreographies belonging to other users
 * still use it. The message gives counts only: those items may be private to their owners.
 */
class FigureInUseException(val noteCount: Long, val choreographyCount: Long) :
    RuntimeException(message(noteCount, choreographyCount)) {

    private companion object {
        fun message(notes: Long, choreographies: Long): String {
            val parts = listOfNotNull(
                plural(notes, "note").takeIf { notes > 0 },
                plural(choreographies, "choreography", "choreographies").takeIf { choreographies > 0 }
            )
            return "This figure can't be deleted: ${parts.joinToString(" and ")} " +
                "belonging to other users ${if (notes + choreographies == 1L) "uses" else "use"} it."
        }

        fun plural(n: Long, one: String, many: String = one + "s") = "$n ${if (n == 1L) one else many}"
    }
}

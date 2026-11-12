package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.TextPaths.Path
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

/**
 * Joiners found at word boundaries however long a run of non-spacing marks is: every entry reads a
 * text of one such run, on a control, in a dialog and in a row, as it reads a short run (FWD-B1
 * sweep: `\b` read back over the whole run at each boundary it checked, so the time grew with the
 * square of the run, about 30 s a reading at a screen's worth).
 */
class JoinersTest {
    private val tuning = PolicyTuning()
    private val paths = TextPaths(Fx.rules())

    /** "Send" with [marks] marks on its last letter, then " and delete". */
    private fun said(marks: Int): String = "Send" + MARK.repeat(marks) + " and delete"

    @Test
    fun `a run of marks as long as a screen's text is read in time, as a short run is`() {
        // Both runs make the first part longer than a row's and a dialog's limit; a control's own label has none.
        val most = tuning.maxScreenText - 200
        val cells =
            Path.entries.flatMap { path ->
                val few = paths.entries(path, said(FEW))
                paths.entries(path, said(most)).zip(few).map { (many, one) ->
                    val name = "$path ${one.first}"
                    cell(name) {
                        val read = assertTimeoutPreemptively(LIMIT, ThrowingSupplier { many.second() }, name)
                        assertEquals(one.second(), read, name)
                    }
                }
            }
        assertAll(cells)
    }
}

/** A non-spacing mark (U+0332 COMBINING LOW LINE), written as a number. */
private val MARK = String(Character.toChars(0x0332))

/** Marks in the short run, enough to pass every length limit of the default tuning. */
private const val FEW = 100

/** How long one entry may take on the longest run: far above what a linear reading needs. */
private val LIMIT: Duration = Duration.ofSeconds(20)

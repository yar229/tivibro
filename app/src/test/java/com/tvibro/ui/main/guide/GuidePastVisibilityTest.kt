package com.tvibro.ui.main.guide

import com.tvibro.data.model.Program
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The setting decides whether the grid keeps what has already ended, so the line it draws has to be
 * the moment a programme finishes and not the moment it starts: a programme running right now stays
 * however long it has been going.
 */
class GuidePastVisibilityTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `a programme that is running now stays`() {
        val running = listOf(program("Running", now - hour, now + hour))

        assertEquals(running, visiblePrograms(running, showPast = false, now = now))
    }

    @Test
    fun `a programme that has ended goes`() {
        val ended = listOf(program("Ended", now - 2 * hour, now - hour))

        assertEquals(emptyList<Program>(), visiblePrograms(ended, showPast = false, now = now))
    }

    @Test
    fun `the ones still to come stay and only the past ones go`() {
        val list = listOf(
            program("Morning", now - 5 * hour, now - 4 * hour),
            program("Now", now - hour, now + hour),
            program("Evening", now + 2 * hour, now + 3 * hour),
        )

        assertEquals(
            listOf("Now", "Evening"),
            visiblePrograms(list, showPast = false, now = now).map { it.title },
        )
    }

    @Test
    fun `a programme ending exactly now counts as finished`() {
        val list = listOf(program("Just ended", now - hour, now))

        assertEquals(emptyList<Program>(), visiblePrograms(list, showPast = false, now = now))
    }

    @Test
    fun `with the setting on nothing is taken away`() {
        val list = listOf(
            program("Morning", now - 5 * hour, now - 4 * hour),
            program("Evening", now + 2 * hour, now + 3 * hour),
        )

        assertEquals(list, visiblePrograms(list, showPast = true, now = now))
    }

    private fun program(title: String, start: Long, stop: Long) =
        Program(channelId = 1, title = title, start = start, stop = stop)
}
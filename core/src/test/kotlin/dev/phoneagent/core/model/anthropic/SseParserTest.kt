package dev.phoneagent.core.model.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val parser = SseParser()
        val events = lines.mapNotNull(parser::line)
        return events + listOfNotNull(parser.finish())
    }

    @Test
    fun `a blank line ends an event and data lines join with newlines`() {
        val events = parse("event: message", "data: {\"a\":", "data:1}", "", "data: two", "")
        assertEquals(listOf(SseEvent("message", "{\"a\":\n1}"), SseEvent(null, "two")), events)
    }

    @Test
    fun `comments, ids, retries and unknown fields are skipped`() {
        val events = parse(": keep-alive", "id: 7", "retry: 100", "foo", "data", "", ":", "")
        // A bare `data` field is an empty data line; the comment-only event dispatches nothing.
        assertEquals(listOf(SseEvent(null, "")), events)
    }

    @Test
    fun `only the first line loses a byte order mark`() {
        val parser = SseParser()
        assertNull(parser.line("﻿: comment"))
        // Later, the mark is part of the field name, which is then unknown.
        parser.line("﻿data: x")
        assertNull(parser.line(""))
        val second = SseParser()
        second.line("﻿data: x")
        assertEquals(SseEvent(null, "x"), second.line(""))
    }

    @Test
    fun `finish returns an unterminated event once and resets`() {
        val parser = SseParser()
        parser.line("event: e")
        parser.line("data: tail")
        assertEquals(SseEvent("e", "tail"), parser.finish())
        assertNull(parser.finish())
        // An event name without data dispatches nothing and does not leak into the next event.
        parser.line("event: lost")
        assertNull(parser.line(""))
        parser.line("data: next")
        assertEquals(SseEvent(null, "next"), parser.line(""))
    }

    @Test
    fun `only one space after the colon is removed`() {
        assertEquals(listOf(SseEvent(null, " two spaces")), parse("data:  two spaces", ""))
    }
}

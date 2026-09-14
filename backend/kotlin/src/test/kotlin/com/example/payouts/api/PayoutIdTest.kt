package com.example.payouts.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The payout id keys the scenario store, so two payouts sharing one is not cosmetic: the
 * second inherits the first's failure injection. The previous generator was seeded
 * `System.currentTimeMillis() % 100_000`, which replays the same range after every restart.
 */
class PayoutIdTest {

    @Test
    fun `ids are unique, and in minting order`() {
        val ids = List(10_000) { newPayoutId() }

        assertEquals(10_000, ids.toSet().size, "a repeated id hands a payout someone else's scenario")
        assertEquals(
            ids.sorted(),
            ids,
            "the v7 prefix is a timestamp then a counter, so lexical order is minting order",
        )
    }

    @Test
    fun `the id carries the wall clock, which is what survives a restart`() {
        // The first 12 hex characters are the 48-bit millisecond timestamp. A per-process
        // counter starts again from its seed; this advances with the clock, so a new process
        // cannot mint an id an old one already used.
        val before = System.currentTimeMillis()
        val stamp = newPayoutId().removePrefix("po-").take(12).toLong(16)
        val after = System.currentTimeMillis()

        assertTrue(stamp in before..after, "embedded timestamp $stamp is outside $before..$after")
    }

    @Test
    fun `the shape references are built from is intact`() {
        val id = newPayoutId()
        val body = id.removePrefix("po-")

        assertTrue(id.startsWith("po-"), "bank and reversal references strip this prefix")
        assertEquals(16, body.length, "timestamp, version nibble and counter")
        assertEquals('7', body[12], "the version nibble says which UUID form this came from")
        // substringAfter returns the whole string when the prefix is absent, so a lost prefix
        // would silently widen every reference rather than fail.
        assertNotEquals(id, id.substringAfter("po-"))
    }
}

package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.ProtocolError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TopicTests {
    @Test
    fun multiLevelWildcard() {
        val filter = TopicFilter.fromOrThrow("sport/tennis/player1/#")
        assertEquals(filter.toString(), "sport/tennis/player1/#")
        assertTrue(filter.matches(TopicName.fromOrThrow("sport/tennis/player1")))
        assertTrue(filter.matches(TopicName.fromOrThrow("sport/tennis/player1/ranking")))
        assertTrue(
            filter.matches(TopicName.fromOrThrow("sport/tennis/player1/score/wimbledon")),
        )

        // Filter-to-filter matching via sealed interface
        val broadFilter = TopicFilter.fromOrThrow("#")
        assertTrue(filter.matches(broadFilter))
        assertTrue(broadFilter.matches(filter))
        val tennisFilter = TopicFilter.fromOrThrow("sport/tennis/#")
        assertTrue(filter.matches(tennisFilter))

        assertFailsWith(ProtocolError::class) {
            TopicName.fromOrThrow("sport/tennis#")
        }
        assertFailsWith(ProtocolError::class) {
            TopicFilter.fromOrThrow("sport/tennis/#/ranking")
        }
    }

    @Test
    fun singleLevelWildcard() {
        assertEquals(TopicFilter.fromOrThrow("/test/hello/").toString(), "/test/hello/")
        val shortFilter = TopicFilter.fromOrThrow("sport/+")
        assertEquals(shortFilter.toString(), "sport/+")
        assertFalse(shortFilter.matches(TopicName.fromOrThrow("sport")))
        assertTrue(shortFilter.matches(TopicName.fromOrThrow("sport/")))

        val filter = TopicFilter.fromOrThrow("sport/tennis/+")
        assertEquals(filter.toString(), "sport/tennis/+")
        assertTrue(filter.matches(TopicName.fromOrThrow("sport/tennis/player1")))
        assertTrue(filter.matches(TopicName.fromOrThrow("sport/tennis/player2")))
        assertFalse(filter.matches(TopicName.fromOrThrow("sport/tennis/player1/ranking")))
        assertNotNull(TopicFilter.fromOrThrow("+"))
        assertNotNull(TopicFilter.fromOrThrow("+/tennis/#"))
        assertFailsWith(ProtocolError::class) {
            TopicFilter.fromOrThrow("sport+")
        }
        assertNotNull(TopicFilter.fromOrThrow("sport/+/player1"))

        val financeTopic = TopicName.fromOrThrow("/finance")
        assertEquals(financeTopic.toString(), "/finance")
        assertTrue(TopicFilter.fromOrThrow("+/+").matches(financeTopic))
        assertTrue(TopicFilter.fromOrThrow("/+").matches(financeTopic))
        assertFalse(TopicFilter.fromOrThrow("+").matches(financeTopic))
    }

    @Test
    fun topicNameRejectsWildcards() {
        assertFailsWith(ProtocolError::class) { TopicName.fromOrThrow("sensors/+/data") }
        assertFailsWith(ProtocolError::class) { TopicName.fromOrThrow("sensors/#") }
        assertFailsWith(ProtocolError::class) { TopicName.fromOrThrow("+") }
        assertFailsWith(ProtocolError::class) { TopicName.fromOrThrow("#") }
    }

    @Test
    fun topicFilterAcceptsWildcards() {
        assertNotNull(TopicFilter.fromOrThrow("sensors/+/data"))
        assertNotNull(TopicFilter.fromOrThrow("sensors/#"))
        assertNotNull(TopicFilter.fromOrThrow("+"))
        assertNotNull(TopicFilter.fromOrThrow("#"))
    }

    @Test
    fun backwardCompatFactory() {
        val name = Topic.fromOrThrow("test/topic", Topic.Type.Name)
        assertTrue(name is TopicName)
        assertEquals("test/topic", name.toString())

        val filter = Topic.fromOrThrow("test/+", Topic.Type.Filter)
        assertTrue(filter is TopicFilter)
        assertEquals("test/+", filter.toString())

        // Sealed interface matches() still works
        assertTrue(name.matches(filter))
        assertTrue(filter.matches(name))
    }

    @Test
    fun equality() {
        val name1 = TopicName.fromOrThrow("a/b/c")
        val name2 = TopicName.fromOrThrow("a/b/c")
        assertEquals(name1, name2)
        assertEquals(name1.hashCode(), name2.hashCode())

        val filter1 = TopicFilter.fromOrThrow("a/+/c")
        val filter2 = TopicFilter.fromOrThrow("a/+/c")
        assertEquals(filter1, filter2)
        assertEquals(filter1.hashCode(), filter2.hashCode())

        // TopicName and TopicFilter are never equal even with same string
        assertFalse(name1.equals(TopicFilter.fromOrThrow("a/b/c")))
    }
}

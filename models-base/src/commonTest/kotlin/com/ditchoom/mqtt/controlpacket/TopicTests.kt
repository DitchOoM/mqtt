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
        val topic = Topic.fromOrThrow("sport/tennis/player1/#", Topic.Type.Filter)
        assertEquals(topic.toString(), "sport/tennis/player1/#")
        assertTrue(validateMatchBothWays(topic, Topic.fromOrThrow("sport/tennis/player1", Topic.Type.Name)))
        assertTrue(validateMatchBothWays(topic, Topic.fromOrThrow("sport/tennis/player1/ranking", Topic.Type.Name)))
        assertTrue(
            validateMatchBothWays(
                topic,
                Topic.fromOrThrow("sport/tennis/player1/score/wimbledon", Topic.Type.Name)
            )
        )

        assertTrue(validateMatchBothWays(topic, Topic.fromOrThrow("#", Topic.Type.Filter)))
        assertTrue(validateMatchBothWays(topic, Topic.fromOrThrow("sport/tennis/#", Topic.Type.Filter)))
        assertFailsWith(ProtocolError::class) {
            Topic.fromOrThrow("sport/tennis#", Topic.Type.Name)
        }
        assertFailsWith(ProtocolError::class) {
            Topic.fromOrThrow("sport/tennis/#/ranking", Topic.Type.Filter)
        }
    }

    private fun validateMatchBothWays(left: Topic?, right: Topic?): Boolean {
        val leftMatches = left?.matches(right) ?: false
        val rightMatches = right?.matches(left) ?: false
        return leftMatches && rightMatches
    }

    @Test
    fun singleLevelWildcard() {
        assertEquals(Topic.fromOrThrow("/test/hello/", Topic.Type.Filter).toString(), "/test/hello/")
        val shortTopic = checkNotNull(Topic.fromOrThrow("sport/+", Topic.Type.Filter))
        assertEquals(shortTopic.toString(), "sport/+")
        assertFalse(validateMatchBothWays(shortTopic, Topic.fromOrThrow("sport", Topic.Type.Name)))
        assertTrue(validateMatchBothWays(shortTopic, Topic.fromOrThrow("sport/", Topic.Type.Name)))

        val topic = checkNotNull(Topic.fromOrThrow("sport/tennis/+", Topic.Type.Filter))
        assertEquals(topic.toString(), "sport/tennis/+")
        assertTrue(validateMatchBothWays(topic, Topic.fromOrThrow("sport/tennis/player1", Topic.Type.Name)))
        assertTrue(validateMatchBothWays(topic, Topic.fromOrThrow("sport/tennis/player2", Topic.Type.Name)))
        assertFalse(validateMatchBothWays(topic, Topic.fromOrThrow("sport/tennis/player1/ranking", Topic.Type.Name)))
        assertNotNull(Topic.fromOrThrow("+", Topic.Type.Filter))
        assertNotNull(Topic.fromOrThrow("+/tennis/#", Topic.Type.Filter))
        assertFailsWith(ProtocolError::class) {
            Topic.fromOrThrow("sport+", Topic.Type.Filter)
        }
        assertNotNull(Topic.fromOrThrow("sport/+/player1", Topic.Type.Filter))

        val financeTopic = checkNotNull(Topic.fromOrThrow("/finance", Topic.Type.Name))
        assertEquals(financeTopic.toString(), "/finance")
        assertTrue(validateMatchBothWays(financeTopic, Topic.fromOrThrow("+/+", Topic.Type.Filter)))
        assertTrue(validateMatchBothWays(financeTopic, Topic.fromOrThrow("/+", Topic.Type.Filter)))
        assertFalse(validateMatchBothWays(financeTopic, Topic.fromOrThrow("+", Topic.Type.Filter)))
    }
}

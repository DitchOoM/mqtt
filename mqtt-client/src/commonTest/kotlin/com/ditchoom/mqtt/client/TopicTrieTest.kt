package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicTrieTest {
    private val trie = TopicTrie<String>()

    @Test
    fun exactMatch() {
        trie.insert(TopicFilter.fromOrThrow("a/b/c"), "handler1")
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a/b/c")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("a/b")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("a/b/c/d")))
    }

    @Test
    fun singleLevelWildcard() {
        trie.insert(TopicFilter.fromOrThrow("a/+/c"), "handler1")
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a/b/c")))
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a/x/c")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("a/b/d")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("a/b/c/d")))
    }

    @Test
    fun multiLevelWildcard() {
        trie.insert(TopicFilter.fromOrThrow("a/#"), "handler1")
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a/b")))
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a/b/c")))
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a/b/c/d/e")))
        // '#' after 'a/' should also match just 'a' with no further segments
        assertEquals(listOf("handler1"), trie.matchAll(TopicName.fromOrThrow("a")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("b/c")))
    }

    @Test
    fun rootMultiLevelWildcard() {
        trie.insert(TopicFilter.fromOrThrow("#"), "catch-all")
        assertEquals(listOf("catch-all"), trie.matchAll(TopicName.fromOrThrow("anything")))
        assertEquals(listOf("catch-all"), trie.matchAll(TopicName.fromOrThrow("a/b/c")))
    }

    @Test
    fun multipleMatches() {
        trie.insert(TopicFilter.fromOrThrow("a/b/c"), "exact")
        trie.insert(TopicFilter.fromOrThrow("a/+/c"), "single")
        trie.insert(TopicFilter.fromOrThrow("a/#"), "multi")
        val matches = trie.matchAll(TopicName.fromOrThrow("a/b/c"))
        assertEquals(3, matches.size)
        assertTrue(matches.contains("exact"))
        assertTrue(matches.contains("single"))
        assertTrue(matches.contains("multi"))
    }

    @Test
    fun insertReturnsOldValue() {
        assertNull(trie.insert(TopicFilter.fromOrThrow("a/b"), "first"))
        assertEquals("first", trie.insert(TopicFilter.fromOrThrow("a/b"), "second"))
        assertEquals(listOf("second"), trie.matchAll(TopicName.fromOrThrow("a/b")))
    }

    @Test
    fun removeHandler() {
        trie.insert(TopicFilter.fromOrThrow("a/b"), "handler")
        assertEquals("handler", trie.remove(TopicFilter.fromOrThrow("a/b")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("a/b")))
        assertTrue(trie.isEmpty())
    }

    @Test
    fun removeNonExistent() {
        assertNull(trie.remove(TopicFilter.fromOrThrow("a/b")))
    }

    @Test
    fun hasMatch() {
        trie.insert(TopicFilter.fromOrThrow("a/+/c"), "handler")
        assertTrue(trie.hasMatch(TopicName.fromOrThrow("a/b/c")))
        assertFalse(trie.hasMatch(TopicName.fromOrThrow("a/b/d")))
    }

    @Test
    fun clear() {
        trie.insert(TopicFilter.fromOrThrow("a/b"), "h1")
        trie.insert(TopicFilter.fromOrThrow("c/d"), "h2")
        trie.clear()
        assertTrue(trie.isEmpty())
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("a/b")))
    }

    @Test
    fun singleSegmentTopic() {
        trie.insert(TopicFilter.fromOrThrow("temperature"), "handler")
        assertEquals(listOf("handler"), trie.matchAll(TopicName.fromOrThrow("temperature")))
        assertEquals(emptyList(), trie.matchAll(TopicName.fromOrThrow("humidity")))
    }

    @Test
    fun plusWildcardAtDifferentPositions() {
        trie.insert(TopicFilter.fromOrThrow("+/b/c"), "start")
        trie.insert(TopicFilter.fromOrThrow("a/b/+"), "end")
        trie.insert(TopicFilter.fromOrThrow("+/+/+"), "all-plus")

        val matches = trie.matchAll(TopicName.fromOrThrow("a/b/c"))
        assertEquals(3, matches.size)
        assertTrue(matches.containsAll(listOf("start", "end", "all-plus")))
    }
}

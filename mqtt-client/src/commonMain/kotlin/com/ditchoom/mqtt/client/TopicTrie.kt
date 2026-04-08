package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName

/**
 * A trie (prefix tree) keyed by topic segments for O(segments) dispatch of
 * incoming publishes to matching subscription handlers.
 *
 * Supports MQTT wildcard matching:
 * - `+` matches exactly one segment at that level
 * - `#` matches zero or more remaining segments (must be last)
 *
 * Thread safety: NOT thread-safe. Callers must synchronize externally if
 * used from multiple coroutines (typically fine since MQTT dispatch is single-threaded).
 */
internal class TopicTrie<T> {
    private val root = TrieNode<T>()

    /** Insert a handler for the given topic filter. Returns any previous handler. */
    fun insert(
        filter: TopicFilter,
        value: T,
    ): T? {
        val segments = filter.toString().split('/')
        var node = root
        for (segment in segments) {
            node = node.children.getOrPut(segment) { TrieNode() }
        }
        val prev = node.value
        node.value = value
        return prev
    }

    /** Remove the handler for the given topic filter. Returns the removed handler, or null. */
    fun remove(filter: TopicFilter): T? {
        val segments = filter.toString().split('/')
        var node = root
        val path = mutableListOf(root)
        for (segment in segments) {
            node = node.children[segment] ?: return null
            path.add(node)
        }
        val removed = node.value
        node.value = null

        // Prune empty leaf nodes back up the path
        for (i in path.lastIndex downTo 1) {
            val child = path[i]
            if (child.value == null && child.children.isEmpty()) {
                val parent = path[i - 1]
                parent.children.entries.removeAll { it.value === child }
            } else {
                break
            }
        }
        return removed
    }

    /**
     * Find all handlers matching the given topic name.
     *
     * A topic name like "a/b/c" matches:
     * - Exact filter "a/b/c"
     * - Single-level wildcards: "a/+/c", "+/b/c", "a/b/+", "+/+/+"
     * - Multi-level wildcards: "#", "a/#", "a/b/#"
     */
    fun matchAll(topicName: TopicName): List<T> {
        val segments = topicName.toString().split('/')
        val results = mutableListOf<T>()
        matchRecursive(root, segments, 0, results)
        return results
    }

    /** Returns true if any handler matches the given topic name. */
    fun hasMatch(topicName: TopicName): Boolean {
        val segments = topicName.toString().split('/')
        return hasMatchRecursive(root, segments, 0)
    }

    /** Returns true if the trie has no handlers. */
    fun isEmpty(): Boolean = root.value == null && root.children.isEmpty()

    /** Remove all handlers. */
    fun clear() {
        root.value = null
        root.children.clear()
    }

    private fun matchRecursive(
        node: TrieNode<T>,
        segments: List<String>,
        depth: Int,
        results: MutableList<T>,
    ) {
        // '#' at any level matches everything remaining
        node.children["#"]?.value?.let { results.add(it) }

        if (depth == segments.size) {
            // Reached end of topic segments — check for exact match
            node.value?.let { results.add(it) }
            return
        }

        val segment = segments[depth]

        // Exact segment match
        node.children[segment]?.let { child ->
            matchRecursive(child, segments, depth + 1, results)
        }

        // '+' wildcard matches this single segment
        node.children["+"]?.let { child ->
            matchRecursive(child, segments, depth + 1, results)
        }
    }

    private fun hasMatchRecursive(
        node: TrieNode<T>,
        segments: List<String>,
        depth: Int,
    ): Boolean {
        if (node.children.containsKey("#") && node.children["#"]?.value != null) return true

        if (depth == segments.size) {
            return node.value != null
        }

        val segment = segments[depth]

        node.children[segment]?.let { child ->
            if (hasMatchRecursive(child, segments, depth + 1)) return true
        }

        node.children["+"]?.let { child ->
            if (hasMatchRecursive(child, segments, depth + 1)) return true
        }

        return false
    }

    private class TrieNode<T> {
        var value: T? = null
        val children: MutableMap<String, TrieNode<T>> = mutableMapOf()
    }
}

package com.ditchoom.mqtt.controlpacket

sealed class Level {
    abstract val value: String
    open val nextLevel: Level? = null
    var isPrefixedWithSlash = false
    var isPostfixedWithSlash = false
    abstract fun matches(other: Level): Boolean
    fun stringValue(): String {
        val next = nextLevel
        val postFix = if (next != null) {
            "/$next"
        } else {
            ""
        }
        return value + postFix
    }

    data class StringLevel(override val value: String, override val nextLevel: Level?) : Level() {
        override fun matches(other: Level): Boolean {
            val otherNextLevel = other.nextLevel
            return when (other) {
                MultiLevelWildcard -> true
                is SingleLevelWildcard ->
                    nextLevel == null || (otherNextLevel != null && nextLevel.matches(otherNextLevel))

                is StringLevel -> {
                    if (nextLevel == null && otherNextLevel == null) {
                        value == other.value
                    } else if (nextLevel != null && otherNextLevel != null) {
                        value == other.value && nextLevel.matches(otherNextLevel)
                    } else (nextLevel is MultiLevelWildcard && otherNextLevel == null) ||
                        (otherNextLevel is MultiLevelWildcard && nextLevel == null)
                }
            }
        }

        override fun toString(): String = stringValue()
    }

    object MultiLevelWildcard : Level() {
        override val value: String = "#"
        override fun matches(other: Level): Boolean = when (other) {
            MultiLevelWildcard -> true
            is SingleLevelWildcard -> false
            is StringLevel -> other.matches(this)
        }

        override fun toString(): String = stringValue()
    }

    data class SingleLevelWildcard(override val nextLevel: Level? = null) : Level() {
        override val value: String = "+"
        override fun matches(other: Level): Boolean = when (other) {
            MultiLevelWildcard -> false
            is SingleLevelWildcard -> true
            is StringLevel -> other.matches(this)
        }

        override fun toString(): String = stringValue()
    }
}

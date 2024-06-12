package com.ditchoom.mqtt.controlpacket

interface ISubscription {
    val topicFilter: Topic
    val maximumQos: QualityOfService

    // mqtt 5
    val noLocal: Boolean
        get() = false
    val retainAsPublished: Boolean
        get() = false

    val retainHandling: RetainHandling
        get() = RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE

    enum class RetainHandling(val value: UByte) {
        SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE(0.toUByte()),
        SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS(1.toUByte()),
        DO_NOT_SEND_RETAINED_MESSAGES(2.toUByte()),
    }
}

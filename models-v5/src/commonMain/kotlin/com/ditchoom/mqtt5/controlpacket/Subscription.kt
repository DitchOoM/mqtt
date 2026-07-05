package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter

data class Subscription(
    override val topicFilter: TopicFilter,
    override val maximumQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
    override val noLocal: Boolean = false,
    override val retainAsPublished: Boolean = false,
    override val retainHandling: RetainHandling = SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE,
) : ISubscription {
    fun size() = topicFilter.toString().utf8Length() + UShort.SIZE_BYTES + Byte.SIZE_BYTES

    companion object {
        fun from(
            topic: String,
            qos: QualityOfService,
            noLocal: Boolean = false,
            retainAsPublished: Boolean = false,
            retainHandlingList: RetainHandling = SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE,
        ) = from(
            listOf(TopicFilter.fromOrThrow(topic)),
            listOf(qos),
            listOf(noLocal),
            listOf(retainAsPublished),
            listOf(retainHandlingList),
        ).first()

        fun from(
            topics: List<TopicFilter>,
            qos: List<QualityOfService>,
            noLocalList: List<Boolean>? = null,
            retainAsPublishedList: List<Boolean>? = null,
            retainHandlingList: List<RetainHandling>? = null,
        ): Set<Subscription> {
            require(topics.size == qos.size) {
                "Non matching topics collection size with the QoS collection size"
            }
            require(noLocalList == null || noLocalList.size == topics.size) {
                "Non matching topics collection size with the noLocalList collection size"
            }
            require(retainAsPublishedList == null || retainAsPublishedList.size == topics.size) {
                "Non matching topics collection size with the retainAsPublishedList collection size"
            }
            require(retainHandlingList == null || retainHandlingList.size == topics.size) {
                "Non matching topics collection size with the retainHandlingList collection size"
            }
            return topics.mapIndexedTo(linkedSetOf()) { index, topic ->
                Subscription(
                    topic,
                    qos[index],
                    noLocalList?.get(index) ?: false,
                    retainAsPublishedList?.get(index) ?: false,
                    retainHandlingList?.get(index) ?: SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE,
                )
            }
        }
    }
}

fun Collection<ISubscription>.size(): Int {
    var size = 0
    for (sub in this) size += (sub as Subscription).size()
    return size
}

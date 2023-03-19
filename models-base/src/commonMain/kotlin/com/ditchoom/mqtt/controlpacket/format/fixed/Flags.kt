package com.ditchoom.mqtt.controlpacket.format.fixed

import kotlin.js.JsName

/**
 * The remaining bits [7-0] of byte can be retrieved as a boolean
 * get the value at an index as a boolean
 */
@JsName("ubyteGet")
fun UByte.get(index: Int) = this.toInt().and(0b01.shl(index)) != 0

package com.ditchoom.mqtt

open class MqttWarning(mandatoryNormativeStatement: String, message: String) :
    Exception("$mandatoryNormativeStatement $message")

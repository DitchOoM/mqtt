package com.ditchoom.mqtt.client.net

expect fun getPlatform(): Platform

enum class Platform {
    Android,
    NonAndroid,
}

package com.ditchoom.gradle

import org.jetbrains.kotlin.konan.target.HostManager

/**
 * Which Kotlin/Native targets this build host declares. A host Kotlin/Native cannot compile on
 * (linux arm64) declares none and still builds the JVM, Android, JS and Wasm targets.
 */
sealed interface NativeHost {
    /** macOS: every Apple target. */
    data object Apple : NativeHost

    /** Linux x86_64: linuxX64 and linuxArm64. */
    data object LinuxX64 : NativeHost

    /** No Kotlin/Native toolchain for this host. */
    data object Unsupported : NativeHost

    companion object {
        val current: NativeHost =
            when {
                HostManager.hostIsMac -> Apple
                HostManager.hostIsLinux && System.getProperty("os.arch") in setOf("amd64", "x86_64") -> LinuxX64
                else -> Unsupported
            }
    }
}

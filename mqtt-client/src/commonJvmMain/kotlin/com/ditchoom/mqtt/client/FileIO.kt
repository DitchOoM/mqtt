package com.ditchoom.mqtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

suspend fun File.iterateFilesFast(accept: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
    try {
        Files.newDirectoryStream(toPath()).use { stream ->
            stream.forEach { path ->
                accept(path.toFile())
            }
        }
    } catch (e: NoSuchMethodException) {
        walkTopDown().forEach { file ->
            accept(file)
        }
    }
}

suspend fun File.oldestFile(accept: (File) -> Boolean) = withContext(Dispatchers.IO) {
    var oldestFile: File? = null
    try {
        var oldestTime: FileTime? = null
        Files.newDirectoryStream(toPath()).use { stream ->
            stream.forEach { path ->
                val file = path.toFile()
                if (accept(file)) {
                    val lastModifiedTime = Files.getLastModifiedTime(path)
                    if (oldestTime == null || lastModifiedTime < oldestTime) {
                        oldestTime = lastModifiedTime
                        oldestFile = file
                    }
                }
            }
        }
    } catch (e: NoSuchMethodException) {
        var oldestTime: Long = Long.MAX_VALUE
        listFiles { dir, name ->
            val file = File(dir, name)
            if (accept(file)) {
                val lastModifiedTime = file.lastModified()
                if (lastModifiedTime < oldestTime) {
                    oldestTime = lastModifiedTime
                    oldestFile = file
                }
            }
            false
        }
    }
    return@withContext oldestFile
}

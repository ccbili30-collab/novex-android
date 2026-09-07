package com.openminis.app.novex.domain

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Never falls back to overwriting a live JSON file with a non-atomic copy. */
internal fun writeNovexAtomicFile(target: File, body: String) {
    check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "无法建立成果目录" }
    val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temporary).use { stream ->
            stream.write(body.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}


package com.kingzcheung.xime.settings

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 方案包下载和解压的体积上限，用来挡住 zip bomb。插件包另有更小的上限。 */
internal object ArchiveSafety {
    const val MAX_COMPRESSED_BYTES = 256L * 1024 * 1024
    const val MAX_ENTRIES = 8_000
    const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024

    fun compressedTooLarge(bytes: Long): Boolean = bytes > MAX_COMPRESSED_BYTES

    fun rejectCompressed(bytes: Long) {
        if (compressedTooLarge(bytes)) throw IOException("方案包超过大小上限")
    }

    fun noteEntry(entries: Int, uncompressed: Long) {
        if (entries > MAX_ENTRIES) throw IOException("方案包条目过多")
        if (uncompressed > MAX_UNCOMPRESSED_BYTES) throw IOException("方案包解压后过大")
    }
}

internal fun InputStream.copyLimited(output: OutputStream, soFar: Long): Long {
    var total = soFar
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
        if (total > ArchiveSafety.MAX_UNCOMPRESSED_BYTES) throw IOException("方案包解压后过大")
        output.write(buffer, 0, read)
    }
}

internal fun File.rejectIfCompressedTooLarge() {
    if (isFile) ArchiveSafety.rejectCompressed(length())
}

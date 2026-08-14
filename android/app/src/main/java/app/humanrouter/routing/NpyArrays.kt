package app.humanrouter.routing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal object NpyArrays {
    fun int32(file: File): Int32 {
        val mapped = map(file, 4)
        return Int32(mapped.first, mapped.second)
    }

    fun uint32(file: File): UInt32 {
        val mapped = map(file, 4)
        return UInt32(mapped.first, mapped.second)
    }

    fun uint16(file: File): UInt16 {
        val mapped = map(file, 2)
        return UInt16(mapped.first, mapped.second)
    }

    fun uint64(file: File): UInt64 {
        val mapped = map(file, 8)
        return UInt64(mapped.first, mapped.second)
    }

    private fun map(file: File, bytesPerValue: Int): Pair<ByteBuffer, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val dataOffset = readDataOffset(channel)
            val bytes = channel.size() - dataOffset
            require(bytes >= 0L && bytes % bytesPerValue == 0L) { "Invalid NPY size: ${file.name}" }
            require(bytes <= Int.MAX_VALUE) { "NPY too large for one mapped buffer: ${file.name}" }
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, dataOffset, bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            return buffer to (bytes / bytesPerValue).toInt()
        }
    }

    private fun readDataOffset(channel: FileChannel): Long {
        val prefix = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        while (prefix.hasRemaining()) {
            if (channel.read(prefix) < 0) error("Truncated NPY header")
        }
        prefix.flip()
        val magic = ByteArray(6)
        prefix.get(magic)
        require(magic.contentEquals(byteArrayOf(0x93.toByte(), 78, 85, 77, 80, 89))) { "Not a NumPy .npy file" }
        val major = prefix.get().toInt() and 0xff
        prefix.get()
        val headerLength: Int
        val fixed: Int
        if (major == 1) {
            headerLength = prefix.short.toInt() and 0xffff
            fixed = 10
        } else {
            headerLength = prefix.int
            fixed = 12
        }
        return (fixed + headerLength).toLong()
    }

    class Int32 internal constructor(private val buffer: ByteBuffer, val size: Int) {
        operator fun get(index: Int): Int = buffer.getInt(index * 4)
    }

    class UInt32 internal constructor(private val buffer: ByteBuffer, val size: Int) {
        operator fun get(index: Int): Int = buffer.getInt(index * 4)
    }

    class UInt16 internal constructor(private val buffer: ByteBuffer, val size: Int) {
        operator fun get(index: Int): Int = buffer.getShort(index * 2).toInt() and 0xffff
    }

    class UInt64 internal constructor(private val buffer: ByteBuffer, val size: Int) {
        operator fun get(index: Int): Long = buffer.getLong(index * 8)
    }
}

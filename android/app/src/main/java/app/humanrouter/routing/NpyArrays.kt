package app.humanrouter.routing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal object NpyArrays {
    fun int32(file: File): Int32 = Int32(map(file, 4))
    fun uint32(file: File): UInt32 = UInt32(map(file, 4))
    fun uint16(file: File): UInt16 = UInt16(map(file, 2))
    fun uint64(file: File): UInt64 = UInt64(map(file, 8))

    private fun map(file: File, bytesPerValue: Int): MappedData {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val header = readHeader(channel)
            val bytes = channel.size() - header.dataOffset
            require(bytes >= 0L && bytes % bytesPerValue == 0L) { "Invalid NPY size: ${file.name}" }
            require(bytes <= Int.MAX_VALUE) { "NPY too large for one mapped buffer: ${file.name}" }
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, header.dataOffset, bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            return MappedData(buffer, (bytes / bytesPerValue).toInt())
        }
    }

    private fun readHeader(channel: FileChannel): Header {
        val prefix = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        channel.read(prefix)
        prefix.flip()
        val magic = ByteArray(6)
        prefix.get(magic)
        require(magic.contentEquals(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))) {
            "Not a NumPy .npy file"
        }
        val major = prefix.get().toInt() and 0xff
        prefix.get() // minor
        val headerLength: Int
        val fixed: Int
        if (major == 1) {
            headerLength = prefix.short.toInt() and 0xffff
            fixed = 10
        } else {
            headerLength = prefix.int
            fixed = 12
        }
        return Header((fixed + headerLength).toLong())
    }

    private data class Header(val dataOffset: Long)
    private data class MappedData(val buffer: ByteBuffer, val size: Int)

    class Int32 internal constructor(private val data: MappedData) {
        val size: Int get() = data.size
        operator fun get(index: Int): Int = data.buffer.getInt(index * 4)
    }

    class UInt32 internal constructor(private val data: MappedData) {
        val size: Int get() = data.size
        operator fun get(index: Int): Int = data.buffer.getInt(index * 4)
    }

    class UInt16 internal constructor(private val data: MappedData) {
        val size: Int get() = data.size
        operator fun get(index: Int): Int = data.buffer.getShort(index * 2).toInt() and 0xffff
    }

    class UInt64 internal constructor(private val data: MappedData) {
        val size: Int get() = data.size
        operator fun get(index: Int): Long = data.buffer.getLong(index * 8)
    }
}

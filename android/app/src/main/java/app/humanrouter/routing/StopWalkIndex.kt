package app.humanrouter.routing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.roundToInt

internal class StopWalkIndex private constructor(
    val byStopId: Map<Int, StopSnap>,
    val byWalkNode: Map<Int, List<StopSnap>>
) {
    data class StopSnap(
        val stopId: Int,
        val walkNode: Int,
        val snapMeters: Int
    )

    companion object {
        fun load(file: File): StopWalkIndex {
            ZipFile(file).use { zip ->
                val stopIds = readNpy(zip.getInputStream(zip.getEntry("stop_id.npy")).readBytes())
                val walkNodes = readNpy(zip.getInputStream(zip.getEntry("walk_node.npy")).readBytes())
                val valid = readNpy(zip.getInputStream(zip.getEntry("valid.npy")).readBytes())
                val snapMeters = readNpy(zip.getInputStream(zip.getEntry("snap_m.npy")).readBytes())

                require(stopIds.count == walkNodes.count && stopIds.count == valid.count && stopIds.count == snapMeters.count)
                val byStop = HashMap<Int, StopSnap>(stopIds.count * 2)
                val byNodeMutable = HashMap<Int, MutableList<StopSnap>>(stopIds.count)

                for (i in 0 until stopIds.count) {
                    if (valid.u8(i) == 0) continue
                    val stopId = stopIds.i64(i).toInt()
                    val node = walkNodes.u32(i)
                    val snap = snapMeters.f32(i).roundToInt().coerceAtLeast(0)
                    val item = StopSnap(stopId, node, snap)
                    byStop[stopId] = item
                    byNodeMutable.getOrPut(node) { ArrayList(1) }.add(item)
                }
                return StopWalkIndex(byStop, byNodeMutable)
            }
        }

        private fun readNpy(bytes: ByteArray): NpyBytes {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(6)
            buffer.get(magic)
            require(magic.contentEquals(byteArrayOf(0x93.toByte(), 78, 85, 77, 80, 89)))
            val major = buffer.get().toInt() and 0xff
            buffer.get()
            val headerLength = if (major == 1) buffer.short.toInt() and 0xffff else buffer.int
            val dataOffset = buffer.position() + headerLength
            val headerText = String(bytes, buffer.position(), headerLength, Charsets.ISO_8859_1)
            val shape = Regex("'shape': \\((\\d+),").find(headerText)?.groupValues?.get(1)?.toInt()
                ?: error("Unsupported NPY shape")
            return NpyBytes(buffer, dataOffset, shape)
        }
    }

    private class NpyBytes(
        private val buffer: ByteBuffer,
        private val dataOffset: Int,
        val count: Int
    ) {
        fun i64(index: Int): Long = buffer.getLong(dataOffset + index * 8)
        fun u32(index: Int): Int = buffer.getInt(dataOffset + index * 4)
        fun u8(index: Int): Int = buffer.get(dataOffset + index).toInt() and 0xff
        fun f32(index: Int): Float = buffer.getFloat(dataOffset + index * 4)
    }
}

package mv.dhivehi.tts.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Memory-mapped int8 embedding table written by conversion/export_csm_onnx.py.
 *
 * Layout (little endian): "DVQ8", int32 version, int32 rows, int32 dim,
 * rows float32 scales, rows*dim int8 values. Only the rows actually used are paged
 * into memory, so the 260 MB text table costs almost no RAM.
 */
class Q8Table(file: File) {
    val rows: Int
    val dim: Int
    private val scales: MappedByteBuffer
    private val data: MappedByteBuffer

    init {
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val header = ch.map(FileChannel.MapMode.READ_ONLY, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { header.get(it) }
            require(String(magic, Charsets.US_ASCII) == "DVQ8") { "Not an embedding table: ${file.name}" }
            require(header.int == 1) { "Unsupported embedding table version: ${file.name}" }
            rows = header.int
            dim = header.int
            val expected = 16L + 4L * rows + rows.toLong() * dim
            require(ch.size() == expected) { "Embedding table ${file.name} is incomplete (${ch.size()} of $expected bytes)" }
            scales = ch.map(FileChannel.MapMode.READ_ONLY, 16, 4L * rows)
            scales.order(ByteOrder.LITTLE_ENDIAN)
            data = ch.map(FileChannel.MapMode.READ_ONLY, 16 + 4L * rows, rows.toLong() * dim)
        }
    }

    /** dst[offset + j] += row[j] for the given row. */
    fun addRow(row: Int, dst: FloatArray, offset: Int = 0) {
        require(row in 0 until rows) { "Embedding row $row out of range" }
        val scale = scales.getFloat(row * 4)
        val base = row * dim
        for (j in 0 until dim) dst[offset + j] += data.get(base + j) * scale
    }

    fun copyRow(row: Int, dst: FloatArray, offset: Int = 0) {
        java.util.Arrays.fill(dst, offset, offset + dim, 0f)
        addRow(row, dst, offset)
    }
}

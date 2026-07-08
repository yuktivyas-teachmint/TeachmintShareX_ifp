package com.teachmint.sharex.airplay

import java.io.ByteArrayOutputStream

/**
 * Minimal Apple binary plist (bplist00) encoder.
 * Supports: Map<String,Any>, List<Any>, String, Long, Int, Boolean.
 *
 * Used to respond to AirPlay 2 SETUP/RECORD requests that use
 * Content-Type: application/x-apple-binary-plist.
 */
object AirPlayBinaryPlist {

    fun encode(root: Any): ByteArray = Encoder().build(root)

    private class Encoder {
        val objects = mutableListOf<Any>()
        // For arrays/dicts maps object-index → flat list of child indices
        // Dicts: [key0, key1, ..., val0, val1, ...]
        val children = mutableMapOf<Int, List<Int>>()

        fun collect(obj: Any): Int {
            val idx = objects.size
            objects.add(obj)
            when (obj) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val m = obj as Map<String, Any>
                    val kIdxs = m.keys.map { collect(it) }
                    val vIdxs = m.values.map { collect(it) }
                    children[idx] = kIdxs + vIdxs
                }
                is List<*> -> children[idx] = obj.map { collect(it!!) }
            }
            return idx
        }

        fun build(root: Any): ByteArray {
            val rootIdx = collect(root)
            val n = objects.size
            val refSize = if (n <= 255) 1 else 2

            val encoded = Array(n) { encodeObj(it, refSize) }

            // Compute per-object offsets (after 8-byte "bplist00" magic)
            val offsets = LongArray(n)
            var pos = 8L
            for (i in 0 until n) { offsets[i] = pos; pos += encoded[i].size }

            val offsetTableOffset = pos
            val offIntSize = when {
                offsetTableOffset <= 0xFFL  -> 1
                offsetTableOffset <= 0xFFFFL -> 2
                else -> 4
            }

            return ByteArrayOutputStream().also { out ->
                out.write("bplist00".toByteArray())
                encoded.forEach { out.write(it) }
                // Offset table
                for (off in offsets) writeInt(out, off, offIntSize)
                // 32-byte trailer
                val tr = ByteArray(32)
                tr[6] = offIntSize.toByte()
                tr[7] = refSize.toByte()
                long8(tr, 8,  n.toLong())
                long8(tr, 16, rootIdx.toLong())
                long8(tr, 24, offsetTableOffset)
                out.write(tr)
            }.toByteArray()
        }

        private fun encodeObj(idx: Int, refSize: Int): ByteArray = when (val o = objects[idx]) {
            is Boolean -> byteArrayOf(if (o) 0x09.toByte() else 0x08.toByte())
            is Double  -> doubleBytes(o)
            is Float   -> doubleBytes(o.toDouble())
            is Int     -> intBytes(o.toLong())
            is Long    -> intBytes(o)
            is String  -> strBytes(o)
            is List<*> -> {
                val refs = children[idx] ?: emptyList()
                val n = refs.size
                val pfx = if (n < 15) byteArrayOf((0xA0 or n).toByte())
                          else byteArrayOf(0xAF.toByte()) + intBytes(n.toLong())
                pfx + refsBytes(refs, refSize)
            }
            is Map<*, *> -> {
                val all  = children[idx] ?: emptyList()
                val half = all.size / 2
                val keys = all.subList(0, half)
                val vals = all.subList(half, all.size)
                val n = keys.size
                val pfx = if (n < 15) byteArrayOf((0xD0 or n).toByte())
                          else byteArrayOf(0xDF.toByte()) + intBytes(n.toLong())
                pfx + refsBytes(keys, refSize) + refsBytes(vals, refSize)
            }
            else -> throw IllegalArgumentException("Unsupported: ${o::class.simpleName}")
        }

        private fun doubleBytes(v: Double): ByteArray {
            val bits = java.lang.Double.doubleToRawLongBits(v)
            return byteArrayOf(
                0x23.toByte(),
                (bits shr 56).toByte(), (bits shr 48).toByte(),
                (bits shr 40).toByte(), (bits shr 32).toByte(),
                (bits shr 24).toByte(), (bits shr 16).toByte(),
                (bits shr  8).toByte(),  bits.toByte(),
            )
        }

        private fun intBytes(v: Long): ByteArray = when {
            v in 0..255         -> byteArrayOf(0x10.toByte(), v.toByte())
            v in 0..65535       -> byteArrayOf(0x11.toByte(), (v shr 8).toByte(), v.toByte())
            v in 0..0xFFFFFFFFL -> byteArrayOf(0x12.toByte(),
                (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
            else                -> byteArrayOf(0x13.toByte(),
                (v shr 56).toByte(), (v shr 48).toByte(), (v shr 40).toByte(), (v shr 32).toByte(),
                (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        }

        private fun strBytes(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            val pfx = if (b.size < 15) byteArrayOf((0x50 or b.size).toByte())
                      else byteArrayOf(0x5F.toByte()) + intBytes(b.size.toLong())
            return pfx + b
        }

        private fun refsBytes(refs: List<Int>, refSize: Int): ByteArray {
            val buf = ByteArray(refs.size * refSize)
            for ((i, r) in refs.withIndex()) when (refSize) {
                1 -> buf[i] = r.toByte()
                2 -> { buf[i*2] = (r shr 8).toByte(); buf[i*2+1] = r.toByte() }
            }
            return buf
        }

        private fun writeInt(out: ByteArrayOutputStream, v: Long, size: Int) = when (size) {
            1 -> out.write(v.toInt())
            2 -> { out.write((v shr 8).toInt()); out.write(v.toInt()) }
            else -> { out.write((v shr 24).toInt()); out.write((v shr 16).toInt())
                      out.write((v shr 8).toInt());  out.write(v.toInt()) }
        }

        private fun long8(buf: ByteArray, off: Int, v: Long) {
            for (i in 0..7) buf[off + i] = ((v shr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }
}

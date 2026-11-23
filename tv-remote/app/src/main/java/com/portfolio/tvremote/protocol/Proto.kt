package com.portfolio.tvremote.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf encoder. The TV protocol only needs varints, strings, bytes and
 * nested messages, so this avoids pulling in protoc + a code generator.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter = apply {
        tag(field, 0); writeRawVarint(out, value)
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, 2); writeRawVarint(out, value.size.toLong()); out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = message(field, ProtoWriter().apply(build))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeRawVarint(out, ((field shl 3) or wireType).toLong())
}

/** Decoded message: field number -> values (Long for varint/fixed, ByteArray for length-delimited). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)
    fun long(field: Int): Long = fields[field]?.firstOrNull() as? Long ?: 0L
    fun int(field: Int): Int = long(field).toInt()
    fun bool(field: Int): Boolean = long(field) != 0L
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }
    fun messages(field: Int): List<ProtoMessage> =
        fields[field].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IllegalArgumentException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalArgumentException("Varint too long")
                }
            }
            fun fixed(n: Int): Long {
                if (pos + n > data.size) throw IllegalArgumentException("Truncated fixed")
                var v = 0L
                for (i in 0 until n) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                pos += n
                return v
            }
            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> readVarint()
                    1 -> fixed(8)
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw IllegalArgumentException("Bad length")
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    5 -> fixed(4)
                    else -> throw IllegalArgumentException("Unsupported wire type in key $key")
                }
                fields.getOrPut(field) { ArrayList() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

internal fun writeRawVarint(out: OutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

/** Both TV ports frame each protobuf message with a varint length prefix. */
object Framing {
    private const val MAX_MESSAGE = 1 shl 20

    fun write(out: OutputStream, message: ByteArray) {
        val buf = ByteArrayOutputStream(message.size + 5)
        writeRawVarint(buf, message.size.toLong())
        buf.write(message)
        out.write(buf.toByteArray())
        out.flush()
    }

    /** Returns null on a clean end of stream before a new message starts. */
    fun read(input: InputStream): ByteArray? {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (shift == 0) return null
                throw EOFException("Stream ended inside a length prefix")
            }
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IllegalArgumentException("Length prefix too long")
        }
        if (len > MAX_MESSAGE) throw IllegalArgumentException("Message too large: $len")
        val data = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(data, read, len - read)
            if (n < 0) throw EOFException("Stream ended inside a message")
            read += n
        }
        return data
    }
}

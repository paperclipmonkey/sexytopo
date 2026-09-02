package org.hwyl.sexytopo.shared.io.store

/**
 * A minimal ZIP writer, so a whole survey can be handed over as one file.
 *
 * Written out longhand because Kotlin/Native and Kotlin/Wasm have no `java.util.zip`, and the
 * archive needed is the simplest one the format allows.
 *
 * Everything is **stored, not deflated**: a survey is four small text files, so DEFLATE would
 * save a few kilobytes at the cost of an implementation on three platforms. `STORED` is part of
 * the format, not a shortcut round it — every unzipper reads it.
 *
 * Only what this needs is implemented: no directories, no Zip64, no encryption, and a fixed
 * timestamp on every entry so the same survey zips to the same bytes. Names are UTF-8 with the
 * language-encoding flag set, so `Šumava` survives the trip.
 */
object Zip {

    /** One file in the archive. */
    class Entry(val name: String, val bytes: ByteArray)

    private const val LOCAL_HEADER = 0x04034b50
    private const val CENTRAL_HEADER = 0x02014b50
    private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50

    /** Stored, i.e. no compression. */
    private const val METHOD_STORED = 0

    /** "Version 2.0 needed", which is what every reader expects to see even for stored entries. */
    private const val VERSION = 20

    /** Bit 11: the name is UTF-8 rather than the format's ancient default code page. */
    private const val UTF8_NAME_FLAG = 1 shl 11

    /**
     * A fixed MS-DOS timestamp: 1 January 1980, the earliest the format can express. Deliberate
     * — a real clock would make the same survey zip to different bytes every time, and the date
     * a survey was *taken* is inside the files anyway.
     */
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x0021

    fun archive(entries: List<Entry>): ByteArray {
        val out = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        val crcs = mutableListOf<Int>()

        for (entry in entries) {
            offsets.add(out.size)
            val name = entry.name.encodeToByteArray()
            val crc = crc32(entry.bytes)
            crcs.add(crc)

            out.int(LOCAL_HEADER)
            out.short(VERSION)
            out.short(UTF8_NAME_FLAG)
            out.short(METHOD_STORED)
            out.short(DOS_TIME)
            out.short(DOS_DATE)
            out.int(crc)
            out.int(entry.bytes.size) // compressed size; the same, because it is not compressed
            out.int(entry.bytes.size)
            out.short(name.size)
            out.short(0) // no extra field
            out.bytes(name)
            out.bytes(entry.bytes)
        }

        val centralDirectoryStart = out.size
        for ((i, entry) in entries.withIndex()) {
            val name = entry.name.encodeToByteArray()
            out.int(CENTRAL_HEADER)
            out.short(VERSION) // made by
            out.short(VERSION) // needed to extract
            out.short(UTF8_NAME_FLAG)
            out.short(METHOD_STORED)
            out.short(DOS_TIME)
            out.short(DOS_DATE)
            out.int(crcs[i])
            out.int(entry.bytes.size)
            out.int(entry.bytes.size)
            out.short(name.size)
            out.short(0) // extra field
            out.short(0) // comment
            out.short(0) // disk number
            out.short(0) // internal attributes
            out.int(0) // external attributes
            out.int(offsets[i])
            out.bytes(name)
        }
        val centralDirectorySize = out.size - centralDirectoryStart

        out.int(END_OF_CENTRAL_DIRECTORY)
        out.short(0) // this disk
        out.short(0) // the disk the central directory starts on
        out.short(entries.size)
        out.short(entries.size)
        out.int(centralDirectorySize)
        out.int(centralDirectoryStart)
        out.short(0) // no archive comment

        return out.toByteArray()
    }

    /** CRC-32, computed on the fly rather than from a table — this runs once over a few kilobytes. */
    internal fun crc32(bytes: ByteArray): Int {
        var crc = 0xFFFFFFFFu
        for (byte in bytes) {
            crc = crc xor (byte.toUInt() and 0xFFu)
            repeat(8) {
                crc = if (crc and 1u != 0u) (crc shr 1) xor 0xEDB88320u else crc shr 1
            }
        }
        return (crc xor 0xFFFFFFFFu).toInt()
    }

    /** A growable byte buffer that writes the little-endian fields the format is made of. */
    private class ByteArrayBuilder {
        private var buffer = ByteArray(1024)
        var size: Int = 0
            private set

        private fun room(extra: Int) {
            if (size + extra <= buffer.size) return
            var capacity = buffer.size
            while (capacity < size + extra) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }

        fun byte(value: Int) {
            room(1)
            buffer[size++] = value.toByte()
        }

        fun short(value: Int) {
            byte(value and 0xFF)
            byte((value shr 8) and 0xFF)
        }

        fun int(value: Int) {
            short(value and 0xFFFF)
            short((value shr 16) and 0xFFFF)
        }

        fun bytes(value: ByteArray) {
            room(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}

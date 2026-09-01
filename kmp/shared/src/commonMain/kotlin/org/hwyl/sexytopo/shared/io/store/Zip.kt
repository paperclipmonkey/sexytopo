package org.hwyl.sexytopo.shared.io.store

/**
 * A minimal ZIP writer, so a whole survey can be handed over as one file.
 *
 * Ported in intent from `control/io/share/SurveyZipSharer`, which builds a zip of the survey's
 * files and gives it to Android's share sheet. This port could read what somebody had unzipped and
 * could not produce the zip, so handing a survey to a caving partner meant exporting four files and
 * hoping they arrived together.
 *
 * ## Why it is written out longhand
 *
 * Java has `java.util.zip` and Kotlin/Native and Kotlin/Wasm do not, so a shared implementation is
 * either a dependency on every platform or a hundred lines here. A hundred lines here, because the
 * archive this needs is the simplest one the format allows.
 *
 * Everything is **stored, not deflated**. A survey is four small text files; the compression would
 * save a few kilobytes and cost a DEFLATE implementation on three platforms, which is the wrong
 * trade by a wide margin. `STORED` is part of the format rather than a shortcut round it - every
 * unzipper reads it, including the Files app, Finder and Windows Explorer.
 *
 * Only what this needs is implemented: no directories, no Zip64, no encryption, no timestamps
 * (every entry is stamped with the same fixed date, so the same survey zips to the same bytes and
 * a test can compare them). Names are UTF-8 with the language-encoding flag set, which is how a
 * cave called `Šumava` survives the trip.
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
     * A fixed MS-DOS timestamp: 1 January 1980, the earliest the format can express.
     *
     * Deliberate. A clock in here would make the same survey produce different bytes every time,
     * which is the defect this port already reports in the Android app's PocketTopo exporter - an
     * archive nobody can diff, compare or test. The date a survey was *taken* is inside the files.
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

    /**
     * CRC-32, the checksum the format requires, computed on the fly rather than from a table.
     *
     * A table would be faster and this runs over a few tens of kilobytes once, when somebody taps
     * share, so the loop is the honest choice: no table to get wrong and nothing to initialise.
     */
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

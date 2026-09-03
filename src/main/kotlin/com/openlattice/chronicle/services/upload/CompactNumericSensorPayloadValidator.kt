package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.sensorkit.CompactNumericSensorPayload
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal object CompactNumericSensorPayloadValidator {
    const val SCHEMA_VERSION = 3
    const val ENCODING = "ieee754-binary64-xor-bytepack-zlib-base64"
    const val TIME_UNIT = "seconds"
    const val QUANTIZED_SCHEMA_VERSION = 2
    private const val QUANTIZED_ENCODING = "delta-zigzag-varint-zlib-base64"
    private const val QUANTIZED_TIME_UNIT = "nanoseconds"

    private const val MAX_SAMPLE_COUNT = 1_000_000
    private const val MAX_UNCOMPRESSED_BYTE_COUNT = 8 * 1_024 * 1_024
    private const val MAX_CHANNEL_COUNT = 128
    private const val MAX_PAYLOAD_LENGTH = 1_000_000

    fun validate(envelope: CompactNumericSensorPayload) {
        val isLossless = envelope.schemaVersion == SCHEMA_VERSION &&
            envelope.encoding == ENCODING && envelope.timeUnit == TIME_UNIT
        val isQuantized = envelope.schemaVersion == QUANTIZED_SCHEMA_VERSION &&
            envelope.encoding == QUANTIZED_ENCODING && envelope.timeUnit == QUANTIZED_TIME_UNIT
        require(isLossless || isQuantized) { "Unsupported compact numeric format" }
        require(envelope.sampleCount in 1..MAX_SAMPLE_COUNT) { "Invalid compact numeric sample count" }
        require(envelope.uncompressedByteCount in 1..MAX_UNCOMPRESSED_BYTE_COUNT) {
            "Invalid compact numeric uncompressed byte count"
        }
        require(envelope.payload.length <= MAX_PAYLOAD_LENGTH) { "Compact numeric payload exceeds maximum length" }
        require(envelope.nominalFrequencyHz?.let { it.isFinite() && it > 0 } != false) {
            "Invalid compact numeric nominal frequency"
        }
        require(envelope.provenance.isNotBlank() && envelope.provenance.length <= 100) {
            "Invalid compact numeric provenance"
        }
        require(envelope.channels.size in 1..MAX_CHANNEL_COUNT) { "Invalid compact numeric channel count" }
        require(envelope.channels.map { it.name }.toSet().size == envelope.channels.size) {
            "Compact numeric channel names must be unique"
        }
        envelope.channels.forEach { channel ->
            require(channel.name.isNotBlank() && channel.name.length <= 100) {
                "Invalid compact numeric channel name"
            }
            require(channel.unit.isNotBlank() && channel.unit.length <= 100) {
                "Invalid compact numeric channel unit"
            }
            if (isQuantized) {
                require(channel.scale?.let { it.isFinite() && it > 0 } == true) {
                    "Quantized compact numeric channels require a positive scale"
                }
            } else {
                require(channel.scale == null) {
                    "Lossless compact numeric channels cannot define a scale"
                }
            }
        }

        val compressed = try {
            Base64.getDecoder().decode(envelope.payload)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid compact numeric base64 payload", ex)
        }
        val binary = inflate(compressed, envelope.uncompressedByteCount)
        if (isLossless) {
            val reader = ByteReader(binary)
            validateLosslessTimestampStream(envelope.sampleCount, reader)
            envelope.channels.forEach {
                validateLosslessChannelStream(envelope.sampleCount, reader)
            }
            require(reader.isAtEnd()) { "Compact numeric payload contains trailing data" }
        } else {
            val reader = VarintReader(binary)
            validateTimestampStream(envelope.sampleCount, reader)
            envelope.channels.forEach { channel ->
                validateChannelStream(envelope.sampleCount, requireNotNull(channel.scale), reader)
            }
            require(reader.isAtEnd()) { "Compact numeric payload contains trailing data" }
        }
    }

    private fun validateLosslessTimestampStream(count: Int, reader: ByteReader) {
        var previous = Double.fromBits(reader.readLongBigEndian())
        require(previous.isFinite() && previous >= 0) { "Invalid compact numeric timestamp" }
        repeat(count - 1) {
            val current = Double.fromBits(reader.readXorEncodedBits())
            require(current.isFinite() && current >= previous) {
                "Compact numeric timestamps must be finite, nonnegative, and monotonic"
            }
            previous = current
        }
    }

    private fun validateLosslessChannelStream(count: Int, reader: ByteReader) {
        require(Double.fromBits(reader.readLongBigEndian()).isFinite()) {
            "Compact numeric channel value is not finite"
        }
        repeat(count - 1) {
            require(Double.fromBits(reader.readXorEncodedBits()).isFinite()) {
                "Compact numeric channel value is not finite"
            }
        }
    }

    private fun validateTimestampStream(count: Int, reader: VarintReader) {
        var current = reader.readSignedVarint()
        require(current >= 0) { "Compact numeric timestamp cannot be negative" }
        if (count == 1) return

        val firstTimestamp = current
        var previousDelta = reader.readSignedVarint()
        current = checkedAdd(current, previousDelta)
        require(current >= firstTimestamp) { "Compact numeric timestamps must be monotonic" }
        var previousTimestamp = current

        repeat(count - 2) {
            val deltaOfDelta = reader.readSignedVarint()
            val delta = checkedAdd(previousDelta, deltaOfDelta)
            current = checkedAdd(previousTimestamp, delta)
            require(current >= previousTimestamp) { "Compact numeric timestamps must be monotonic" }
            previousTimestamp = current
            previousDelta = delta
        }
    }

    private fun validateChannelStream(count: Int, scale: Double, reader: VarintReader) {
        var current = reader.readSignedVarint()
        require((current.toDouble() * scale).isFinite()) { "Compact numeric channel value is not finite" }
        repeat(count - 1) {
            current = checkedAdd(current, reader.readSignedVarint())
            require((current.toDouble() * scale).isFinite()) { "Compact numeric channel value is not finite" }
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (ex: ArithmeticException) {
        throw IllegalArgumentException("Compact numeric payload arithmetic overflow", ex)
    }

    private fun inflate(source: ByteArray, expectedByteCount: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(source)
            val destination = ByteArray(expectedByteCount)
            var offset = 0
            while (!inflater.finished() && offset < destination.size) {
                val decoded = inflater.inflate(destination, offset, destination.size - offset)
                if (decoded == 0) {
                    require(!inflater.needsDictionary() && !inflater.needsInput()) {
                        "Incomplete compact numeric compressed payload"
                    }
                    throw IllegalArgumentException("Compact numeric decompression made no progress")
                }
                offset += decoded
            }
            require(inflater.finished() && offset == expectedByteCount && inflater.remaining == 0) {
                "Compact numeric decompressed length mismatch"
            }
            return destination
        } catch (ex: DataFormatException) {
            throw IllegalArgumentException("Invalid compact numeric compressed payload", ex)
        } finally {
            inflater.end()
        }
    }

    private class VarintReader(private val data: ByteArray) {
        private var index = 0

        fun isAtEnd(): Boolean = index == data.size

        fun readSignedVarint(): Long {
            val encoded = readUnsignedVarint()
            return (encoded ushr 1) xor -(encoded and 1L)
        }

        private fun readUnsignedVarint(): Long {
            var value = 0L
            var shift = 0
            while (index < data.size && shift <= 63) {
                val byte = data[index++].toInt() and 0xff
                require(shift != 63 || byte and 0xfe == 0) { "Compact numeric varint overflow" }
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("Malformed compact numeric varint")
        }
    }

    private class ByteReader(private val data: ByteArray) {
        private var index = 0
        private var previousBits = 0L

        fun isAtEnd(): Boolean = index == data.size

        fun readLongBigEndian(): Long {
            var value = 0L
            repeat(Long.SIZE_BYTES) {
                value = (value shl 8) or readByte().toLong()
            }
            previousBits = value
            return value
        }

        fun readXorEncodedBits(): Long {
            val header = readByte()
            if (header == 0) return previousBits

            val leadingByteCount = header ushr 4
            val significantByteCount = header and 0x0f
            require(
                leadingByteCount <= 7 &&
                    significantByteCount in 1..8 &&
                    leadingByteCount + significantByteCount <= 8
            ) { "Malformed compact numeric lossless stream" }
            val trailingByteCount = 8 - leadingByteCount - significantByteCount
            var significantBits = 0L
            repeat(significantByteCount) {
                significantBits = (significantBits shl 8) or readByte().toLong()
            }
            previousBits = previousBits xor (significantBits shl (trailingByteCount * 8))
            return previousBits
        }

        private fun readByte(): Int {
            require(index < data.size) { "Malformed compact numeric lossless stream" }
            return data[index++].toInt() and 0xff
        }
    }
}

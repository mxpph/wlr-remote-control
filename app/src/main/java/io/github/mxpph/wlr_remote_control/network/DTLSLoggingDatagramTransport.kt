package io.github.mxpph.wlr_remote_control.network

import android.util.Log
import org.bouncycastle.tls.DatagramTransport

private const val TAG: String = "WlrDtlsClient"

internal fun wrapDTLSTransportForLogging(delegate: DatagramTransport, enabled: Boolean): DatagramTransport {
    return if (enabled) {
        DTLSLoggingDatagramTransport(delegate)
    } else {
        delegate
    }
}

private class DTLSLoggingDatagramTransport(
    private val delegate: DatagramTransport
) : DatagramTransport {

    companion object {
        private const val DTLS_RECORD_HEADER_SIZE = 13
        private const val DTLS_CONTENT_TYPE_CHANGE_CIPHER_SPEC = 20
        private const val DTLS_CONTENT_TYPE_ALERT = 21
        private const val DTLS_CONTENT_TYPE_HANDSHAKE = 22
        private const val DTLS_CONTENT_TYPE_APPLICATION_DATA = 23
    }

    override fun getReceiveLimit(): Int = delegate.receiveLimit

    override fun getSendLimit(): Int = delegate.sendLimit

    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        val received = delegate.receive(buf, off, len, waitMillis)
        if (received <= 0) {
            return received
        }

        val datagramEnd = off + received
        var cursor = off
        var recordIndex = 0
        while (cursor + DTLS_RECORD_HEADER_SIZE <= datagramEnd) {
            val contentType = u8(buf, cursor)
            val versionMajor = u8(buf, cursor + 1)
            val versionMinor = u8(buf, cursor + 2)
            val epoch = readU16(buf, cursor + 3)
            val sequence = readU48(buf, cursor + 5)
            val recordLength = readU16(buf, cursor + 11)
            val fragmentStart = cursor + DTLS_RECORD_HEADER_SIZE
            val fragmentEnd = fragmentStart + recordLength
            if (fragmentEnd > datagramEnd) {
                Log.w(
                    TAG,
                    "Malformed DTLS datagram: record[$recordIndex] len=$recordLength exceeds remaining=${datagramEnd - fragmentStart}"
                )
                break
            }

            val details = when (contentType) {
                DTLS_CONTENT_TYPE_ALERT -> {
                    if (recordLength < 2) {
                        "alert=truncated($recordLength B)"
                    } else {
                        val level = u8(buf, fragmentStart)
                        val description = u8(buf, fragmentStart + 1)
                        "alert=${alertLevelName(level)}/${alertDescriptionName(description)}($description)"
                    }
                }

                DTLS_CONTENT_TYPE_HANDSHAKE -> {
                    if (recordLength < 1) {
                        "handshake=truncated"
                    } else {
                        val handshakeType = u8(buf, fragmentStart)
                        "handshake=${handshakeTypeName(handshakeType)}($handshakeType)"
                    }
                }

                DTLS_CONTENT_TYPE_CHANGE_CIPHER_SPEC -> {
                    if (recordLength >= 1) {
                        "ccsType=${u8(buf, fragmentStart)}"
                    } else {
                        "ccs=truncated"
                    }
                }

                else -> {
                    "payloadHex=${toHexPreview(buf, fragmentStart, recordLength)}"
                }
            }

            Log.d(
                TAG,
                "Received DTLS record[$recordIndex]: type=${contentTypeName(contentType)}($contentType) " +
                    "version=${protocolVersionName(versionMajor, versionMinor)} epoch=$epoch seq=$sequence " +
                    "len=$recordLength $details"
            )

            cursor = fragmentEnd
            recordIndex += 1
        }

        if (cursor < datagramEnd) {
            Log.w(
                TAG,
                "Trailing undecoded DTLS bytes: ${datagramEnd - cursor}"
            )
        }
        return received
    }

    override fun send(buf: ByteArray, off: Int, len: Int) {
        delegate.send(buf, off, len)
    }

    override fun close() {
        delegate.close()
    }


    private fun u8(buf: ByteArray, index: Int): Int = buf[index].toInt() and 0xFF

    private fun readU16(buf: ByteArray, index: Int): Int {
        return (u8(buf, index) shl 8) or u8(buf, index + 1)
    }

    private fun readU48(buf: ByteArray, index: Int): Long {
        return (u8(buf, index).toLong() shl 40) or
                (u8(buf, index + 1).toLong() shl 32) or
                (u8(buf, index + 2).toLong() shl 24) or
                (u8(buf, index + 3).toLong() shl 16) or
                (u8(buf, index + 4).toLong() shl 8) or
                u8(buf, index + 5).toLong()
    }

    private fun contentTypeName(contentType: Int): String {
        return when (contentType) {
            DTLS_CONTENT_TYPE_CHANGE_CIPHER_SPEC -> "change_cipher_spec"
            DTLS_CONTENT_TYPE_ALERT -> "alert"
            DTLS_CONTENT_TYPE_HANDSHAKE -> "handshake"
            DTLS_CONTENT_TYPE_APPLICATION_DATA -> "application_data"
            else -> "unknown"
        }
    }

    private fun protocolVersionName(major: Int, minor: Int): String {
        return when {
            major == 0xFE && minor == 0xFF -> "DTLSv1.0"
            major == 0xFE && minor == 0xFD -> "DTLSv1.2"
            else -> "0x${major.toString(16)}${minor.toString(16)}"
        }
    }

    private fun handshakeTypeName(type: Int): String {
        return when (type) {
            0 -> "hello_request"
            1 -> "client_hello"
            2 -> "server_hello"
            11 -> "certificate"
            12 -> "server_key_exchange"
            13 -> "certificate_request"
            14 -> "server_hello_done"
            15 -> "certificate_verify"
            16 -> "client_key_exchange"
            20 -> "finished"
            else -> "unknown"
        }
    }

    private fun alertLevelName(level: Int): String {
        return when (level) {
            1 -> "warning"
            2 -> "fatal"
            else -> "unknown"
        }
    }

    private fun alertDescriptionName(description: Int): String {
        return when (description) {
            0 -> "close_notify"
            10 -> "unexpected_message"
            20 -> "bad_record_mac"
            21 -> "decryption_failed_RESERVED"
            22 -> "record_overflow"
            30 -> "decompression_failure"
            41 -> "no_certificate_RESERVED"
            42 -> "bad_certificate"
            43 -> "unsupported_certificate"
            44 -> "certificate_revoked"
            45 -> "certificate_expired"
            46 -> "certificate_unknown"
            47 -> "illegal_parameter"
            48 -> "unknown_ca"
            49 -> "access_denied"
            50 -> "decode_error"
            51 -> "decrypt_error"
            60 -> "export_restriction_RESERVED"
            70 -> "protocol_version"
            71 -> "insufficient_security"
            80 -> "internal_error"
            90 -> "user_canceled"
            100 -> "no_renegotiation"
            110 -> "unsupported_extension"
            else -> "unknown"
        }
    }

    private fun toHexPreview(buf: ByteArray, start: Int, length: Int, maxBytes: Int = 12): String {
        val bytesToShow = minOf(length, maxBytes)
        val hex = (0 until bytesToShow)
            .joinToString(separator = " ") { i -> "%02x".format(u8(buf, start + i)) }
        return if (length > maxBytes) "$hex ..." else hex
    }
}



package com.example.wlr_remote_control.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

private const val TAG: String = "WlrDtlsClient"

class WlrDtlsClient {
    private var dtlsTransport: DTLSTransport? = null
    private var socket: DatagramSocket? = null

    suspend fun connect(ip: String, port: Int, psk: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting on $ip:$port")
            val address = InetAddress.getByName(ip)
            socket = DatagramSocket().apply {
                connect(address, port)
                soTimeout = HANDSHAKE_TIMEOUT_MS
            }
            val udpTransport = UDPTransport(socket, DEFAULT_MTU)
            val crypto = BcTlsCrypto(SecureRandom())
            val identity = BasicTlsPSKIdentity(
                PSK_IDENTITY.toByteArray(Charsets.UTF_8),
                psk.toByteArray(Charsets.UTF_8)
            )
            val client = object : PSKTlsClient(crypto, identity) {
                override fun getProtocolVersions(): Array<out ProtocolVersion?> {
                    return arrayOf(ProtocolVersion.DTLSv12)
                }
                override fun getSupportedCipherSuites(): IntArray {
                    return intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_CCM_8, CipherSuite.TLS_PSK_WITH_AES_128_CCM)
                }
            }
            val protocol = DTLSClientProtocol()
            dtlsTransport = protocol.connect(client, udpTransport)
            true
        } catch (e: Exception) {
            Log.e(TAG, "exception", e)
            Log.e(TAG, "caused by", e.cause)
            close()
            false
        }
    }

    fun sendMousePacket(dx: Int, dy: Int, button: Int, buttonState: Int): Boolean {
        val transport = dtlsTransport ?: return false
        return try {
            val payload = encodeMousePacket(dx, dy, button, buttonState)
            transport.send(payload, 0, payload.size)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        try {
            dtlsTransport?.close()
            socket?.close()
        } finally {
            dtlsTransport = null
            socket = null
        }
    }

    companion object {
        private const val PSK_IDENTITY = "wlr-remote"
        private const val DEFAULT_MTU = 1500
        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        internal fun encodeMousePacket(dx: Int, dy: Int, button: Int, buttonState: Int): ByteArray {
            return ByteBuffer.allocate(12)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(dx)
                .putInt(dy)
                .putShort(button.toShort())
                .putShort(buttonState.toShort())
                .array()
        }
    }
}


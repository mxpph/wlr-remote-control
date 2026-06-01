package io.github.mxpph.wlr_remote_control.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.AlertLevel
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsTimeoutException
import org.bouncycastle.tls.UDPTransport
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds

private const val TAG: String = "WlrDtlsClient"

class WlrDtlsClient(
    private val enablePacketLogging: Boolean = false
) {
    private var dtlsTransport: DTLSTransport? = null
    private var socket: DatagramSocket? = null
    @Volatile
    private var lastPeerAlert: PeerAlert? = null

    suspend fun connect(ip: String, port: Int, psk: String): DTLSOperationResult = withContext(Dispatchers.IO) {
        try {
            clearPeerAlert()
            Log.i(TAG, "Connecting on $ip:$port")
            val address = InetAddress.getByName(ip)
            socket = DatagramSocket().apply {
                connect(address, port)
                soTimeout = SOCKET_TIMEOUT_MS
            }
            val udpTransport = UDPTransport(socket, DEFAULT_MTU)
            val transport = wrapDTLSTransportForLogging(udpTransport, enablePacketLogging)
            val crypto = BcTlsCrypto(SecureRandom())
            val identity = BasicTlsPSKIdentity(
                PSK_IDENTITY.toByteArray(Charsets.UTF_8),
                psk.toByteArray(Charsets.UTF_8)
            )
            val client = object : PSKTlsClient(crypto, identity) {
                override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
                    rememberPeerAlert(alertLevel, alertDescription)
                    Log.w(
                        TAG,
                        "DTLS alert received from peer: level=${AlertLevel.getText(alertLevel)} " +
                            "description=${AlertDescription.getText(alertDescription)}"
                    )
                    super.notifyAlertReceived(alertLevel, alertDescription)
                }

                override fun getHandshakeTimeoutMillis(): Int {
                    return HANDSHAKE_TIMEOUT_MS
                }

                override fun getHandshakeResendTimeMillis(): Int {
                    return HANDSHAKE_RESEND_MS
                }

                override fun getProtocolVersions(): Array<out ProtocolVersion?> {
                    return arrayOf(ProtocolVersion.DTLSv12)
                }
                override fun getSupportedCipherSuites(): IntArray {
                    return intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_CCM_8, CipherSuite.TLS_PSK_WITH_AES_128_CCM)
                }
            }
            val protocol = DTLSClientProtocol()
            dtlsTransport = protocol.connect(client, transport)
            DTLSOperationResult.Success
        } catch (e: TlsTimeoutException) {
            Log.e(TAG, "DTLS handshake timed out: ${e.message}")
            close()
            DTLSOperationResult.Failure("Could not reach server. Check that the password is correct.")
        } catch (e: Exception) {
            Log.e(TAG, "DTLS connect failed: ${e::class.java.simpleName}: ${e.message}")
            val peerAlert = lastPeerAlert
            close()
            if (peerAlert != null) {
                DTLSOperationResult.Failure(mapAlertToUserMessage(peerAlert.description))
            } else {
                DTLSOperationResult.Failure(e.message ?: "Failed to connect to server")
            }
        }
    }

    fun sendMousePacket(dx: Int, dy: Int, button: Int, buttonState: Int): DTLSOperationResult {
        val transport = dtlsTransport ?: return DTLSOperationResult.Failure("Not connected")
        return try {
            val payload = encodeMousePacket(dx, dy, button, buttonState)
            transport.send(payload, 0, payload.size)
            DTLSOperationResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "DTLS send failed: ${e::class.java.simpleName}: ${e.message}")
            val peerAlert = lastPeerAlert
            close()
            if (peerAlert != null) {
                DTLSOperationResult.Failure(mapAlertToUserMessage(peerAlert.description))
            } else {
                DTLSOperationResult.Failure("Connection lost")
            }
        }
    }

    fun close() {
        try {
            dtlsTransport?.close()
            socket?.close()
        } finally {
            dtlsTransport = null
            socket = null
            clearPeerAlert()
        }
    }

    private fun rememberPeerAlert(level: Short, description: Short) {
        lastPeerAlert = PeerAlert(level, description)
    }

    private fun clearPeerAlert() {
        lastPeerAlert = null
    }

    companion object {
        private const val PSK_IDENTITY = "wlr-remote"
        private const val DEFAULT_MTU = 1500
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 3_000  // Overall handshake timeout
        private const val HANDSHAKE_RESEND_MS = 1_000   // Time between retransmissions

        internal fun encodeMousePacket(dx: Int, dy: Int, button: Int, buttonState: Int): ByteArray {
            return ByteBuffer.allocate(12)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(dx)
                .putInt(dy)
                .putShort(button.toShort())
                .putShort(buttonState.toShort())
                .array()
        }

        private fun mapAlertToUserMessage(alertDescription: Short): String {
            return when (alertDescription) {
                AlertDescription.close_notify -> "Server closed the connection"
                AlertDescription.internal_error -> "Server reported an internal error"
                // No other types should be received under normal circumstances
                else -> "Connection failed: ${AlertDescription.getText(alertDescription)}"
            }
        }
    }

    sealed interface DTLSOperationResult {
        data object Success : DTLSOperationResult
        data class Failure(
            val message: String,
        ) : DTLSOperationResult
    }

    private data class PeerAlert(
        val level: Short,
        val description: Short,
    )
}


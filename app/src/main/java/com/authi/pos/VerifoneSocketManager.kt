package com.authi.pos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * TCP socket manager for the Verifone POSLink protocol.
 *
 * 2-way handshake (when seqref / merchant ID is included in the message):
 *   1. POS   → Terminal : [STX][seqref,CMD,...][ETX][LRC]
 *   2. Terminal → POS   : ACK (0x06)
 *   3. Terminal → POS   : [STX][seqref,DSP,1,<status>,...][ETX][LRC]  ← zero or more intermediate
 *   4. POS   → Terminal : ACK (for each intermediate)
 *   5. Terminal → POS   : [STX][seqref,CMD,<result>,...][ETX][LRC]     ← final response
 *   6. POS   → Terminal : ACK
 */
class VerifoneSocketManager {

    private var socket: Socket? = null

    val isConnected: Boolean
        get() = socket?.let { !it.isClosed && it.isConnected } ?: false

    // ── Connection ──────────────────────────────────────────────────────────

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 10_000)
            s.keepAlive = true
            s.tcpNoDelay = true
            socket = s
            Log.i(TAG, "Connected to $host:$port")
            Unit
        }
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        Log.i(TAG, "Disconnected")
    }

    // ── Send / Receive ──────────────────────────────────────────────────────

    /**
     * Sends a pre-framed POSLink [message] (STX…ETX…LRC) to the terminal and waits
     * for the final response, automatically ACK-ing and skipping any intermediate DSP
     * status messages the terminal sends during a 2-way transaction.
     *
     * [onStatus] is invoked (on the IO thread) for each intermediate DSP message so
     * the caller can log terminal status (e.g. "PLEASE WAIT", "AWAITING CARD").
     *
     * Returns an empty ByteArray (not a failure) when the terminal operates in 1-way
     * mode and sends no response. Throws (via Result.failure) on NAK or socket errors.
     */
    suspend fun sendAndReceive(
        message: ByteArray,
        timeoutMs: Long = 60_000,
        onStatus: ((String) -> Unit)? = null
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext Result.failure(Exception("Not connected"))
        runCatching {
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            sock.soTimeout = timeoutMs.coerceAtMost(120_000).toInt()

            Log.d(TAG, "TX (${message.size}B): ${hex(message)}  |  ${PosLinkProtocol.prettyPrint(message)}")
            out.write(message)
            out.flush()

            var finalResponse: ByteArray? = null

            // Keep reading messages until we receive the final non-DSP response
            while (finalResponse == null) {
                val received = readOneMessage(inp) ?: break  // timeout or EOF → no response

                Log.d(TAG, "RX message (${received.size}B): ${hex(received)}  |  ${PosLinkProtocol.prettyPrint(received)}")

                // ACK every received message
                out.write(byteArrayOf(PosLinkProtocol.ACK))
                out.flush()

                val cmd = PosLinkProtocol.extractCommand(received)
                Log.d(TAG, "RX command: $cmd")

                if (cmd == "DSP") {
                    // Intermediate status update — notify caller and keep waiting
                    val statusText = PosLinkProtocol.extractDspText(received)
                    Log.d(TAG, "RX intermediate DSP: $statusText")
                    onStatus?.invoke(statusText)
                } else {
                    finalResponse = received
                }
            }

            finalResponse ?: run {
                Log.d(TAG, "RX no response — treating as 1-way")
                ByteArray(0)
            }
        }
    }

    /**
     * Reads exactly one complete STX…ETX…LRC message from [inp].
     * Returns null on timeout or EOF before a complete message is received.
     */
    private fun readOneMessage(inp: java.io.InputStream): ByteArray? {
        val accumulator = mutableListOf<Byte>()
        val buf = ByteArray(4096)
        var foundEtx = false

        outer@ while (true) {
            val n = try {
                inp.read(buf)
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "RX timeout — no response (1-way mode or slow terminal)")
                break
            }
            if (n == -1) {
                Log.d(TAG, "RX stream closed by terminal")
                break
            }

            val chunk = buf.copyOf(n)
            Log.d(TAG, "RX chunk (${n}B): ${hex(chunk)}  |  ${prettyChunk(chunk)}")

            for (i in 0 until n) {
                val b = buf[i]
                when {
                    b == PosLinkProtocol.NAK && accumulator.isEmpty() -> {
                        Log.w(TAG, "RX NAK — terminal rejected our message (bad LRC or framing)")
                        throw Exception("NAK from terminal — check LRC or message framing")
                    }
                    b == PosLinkProtocol.ACK && accumulator.isEmpty() ->
                        Log.d(TAG, "RX ACK — terminal acknowledged our message")
                    b == PosLinkProtocol.STX ->
                        accumulator.add(b)
                    accumulator.isNotEmpty() -> {
                        accumulator.add(b)
                        if (b == PosLinkProtocol.ETX) foundEtx = true
                        else if (foundEtx) break@outer  // LRC byte received — message complete
                    }
                }
            }
        }

        return if (accumulator.isNotEmpty()) accumulator.toByteArray() else null
    }

    /**
     * Sends POL to verify the terminal is alive and listening.
     */
    suspend fun poll(timeoutMs: Long = 10_000): Boolean =
        sendAndReceive(PosLinkProtocol.poll(), timeoutMs).isSuccess

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun prettyChunk(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when (b) {
                PosLinkProtocol.STX -> append("[STX]")
                PosLinkProtocol.ETX -> append("[ETX]")
                PosLinkProtocol.ACK -> append("[ACK]")
                PosLinkProtocol.NAK -> append("[NAK]")
                else -> if (v in 32..126) append(v.toChar()) else append("[%02X]".format(v))
            }
        }
    }

    companion object {
        private const val TAG = "AuthiPOS"
    }
}

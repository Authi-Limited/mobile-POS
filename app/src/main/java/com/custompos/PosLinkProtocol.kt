package com.custompos

/**
 * Verifone POSLink wire protocol implementation.
 *
 * Wire format:  STX (0x02) | body (ASCII, comma-delimited) | ETX (0x03) | LRC
 * LRC:          XOR of every byte from body[0] through ETX (inclusive); STX excluded.
 * Seqref:       When a merchant ID (seqref) is supplied it is prepended to the body:
 *               "<seqref>,CMD,..." — this enables 2-way mode on the terminal (TCP response).
 *               Without seqref the terminal uses 1-way mode and sends no TCP reply.
 *
 * Response field layout (2-way mode, PUR/REF):
 *   [0]=seqref  [1]=CMD  [2]=txnType  [3]=amount  [4]=cashout
 *   [5]=resultCode(00=approved)  [6]=statusText  [7]=authCode  [8]=RRN
 *   [9]=maskedPAN  [10]=cardType
 */
object PosLinkProtocol {

    const val STX: Byte = 0x02
    const val ETX: Byte = 0x03
    const val ACK: Byte = 0x06
    const val NAK: Byte = 0x15

    private val KNOWN_COMMANDS = setOf(
        "PUR", "REF", "SET", "CAN", "POL", "TTX", "DSP", "NFO", "ENQ", "CSH", "CHQ"
    )

    // ── Request builders ────────────────────────────────────────────────────
    //
    // PUR, REF, CAN accept a seqref. When non-blank it is prepended as "<seqref>,CMD,…"
    // which tells the terminal to use 2-way mode and echo responses back over TCP.
    // POL, SET, DSP, TTX are maintenance commands that run without a seqref.

    // PUR: [seqref,]PUR,<merchantIdx>,<amount>,<cashback>,<displayText>,<AllowCredit><ReturnReceipt>,
    // e.g. "D25310001,PUR,1,000010.00,000000.00,Test Text,YY,"
    fun purchase(
        amountDollars: Double,
        displayText: String = "",
        cashbackDollars: Double = 0.0,
        allowCredit: Boolean = true,
        returnReceipt: Boolean = true,
        merchantIdx: Int = 1,
        seqref: String = ""
    ): ByteArray {
        val p = if (seqref.isBlank()) "" else "$seqref,"
        val flags = "${if (allowCredit) 'Y' else 'N'}${if (returnReceipt) 'Y' else 'N'}"
        return frame("${p}PUR,$merchantIdx,${dollars(amountDollars)},${dollars(cashbackDollars)},$displayText,$flags,")
    }

    // REF format assumed to mirror PUR
    fun refund(
        amountDollars: Double,
        displayText: String = "",
        merchantIdx: Int = 1,
        seqref: String = ""
    ): ByteArray {
        val p = if (seqref.isBlank()) "" else "$seqref,"
        return frame("${p}REF,$merchantIdx,${dollars(amountDollars)},0.00,$displayText,YY,")
    }

    fun cancel(seqref: String = ""): ByteArray {
        val p = if (seqref.isBlank()) "" else "$seqref,"
        return frame("${p}CAN,")
    }

    fun settlement(): ByteArray = frame("SET,1,")

    fun poll(): ByteArray = frame("POL,1,1,")

    fun testTransaction(): ByteArray = frame("TTX,1,")

    fun displayMessage(text: String, merchantIdx: Int = 1): ByteArray =
        frame("DSP,$merchantIdx,NFO,$text,")

    // ── Framing ─────────────────────────────────────────────────────────────

    fun frame(body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.US_ASCII)
        val msg = ByteArray(1 + bodyBytes.size + 2)
        msg[0] = STX
        bodyBytes.copyInto(msg, 1)
        msg[1 + bodyBytes.size] = ETX
        var lrc = 0
        for (i in 1 until msg.size - 1) lrc = lrc xor (msg[i].toInt() and 0xFF)
        msg[msg.size - 1] = lrc.toByte()
        return msg
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    data class Response(
        val command: String,
        val resultCode: String,
        val statusText: String,
        val authCode: String,
        val rrn: String,
        val maskedPan: String,
        val cardType: String,
        val fields: List<String>,
        val rawBody: String,
        val lrcValid: Boolean
    ) {
        val approved: Boolean
            get() = resultCode == "00" ||
                    statusText.uppercase().let { it.contains("ACCEPT") || it.contains("APPROV") }

        fun field(index: Int): String = fields.getOrElse(index) { "" }
    }

    fun parseResponse(data: ByteArray): Response {
        val stxIdx = data.indexOfFirst { it == STX }
        val etxIdx = data.indexOfFirst { it == ETX }

        val body = when {
            stxIdx >= 0 && etxIdx > stxIdx ->
                String(data, stxIdx + 1, etxIdx - stxIdx - 1, Charsets.US_ASCII)
            else ->
                data.filter { (it.toInt() and 0xFF) in 32..126 }
                    .toByteArray().toString(Charsets.US_ASCII).trim()
        }

        val lrcValid = if (etxIdx >= 0 && data.size > etxIdx + 1) {
            var expected = 0
            for (i in (stxIdx + 1)..etxIdx) expected = expected xor (data[i].toInt() and 0xFF)
            expected.toByte() == data[etxIdx + 1]
        } else false

        val allFields = body.split(",")

        // If first field is not a known POSLink command, it's a seqref — skip it
        val offset = if (allFields.isEmpty() || allFields[0] in KNOWN_COMMANDS) 0 else 1

        val command    = allFields.getOrElse(offset) { "" }
        // PUR/REF layout: [seqref,]CMD,txnType,amount,cashout,resultCode,statusText,authCode,rrn,...
        val resultCode = allFields.getOrElse(offset + 4) { "" }.let {
            if (it.matches(Regex("[0-9A-Fa-f]{2}"))) it else ""
        }
        val statusText = allFields.getOrElse(offset + 5) { "" }
        val authCode   = allFields.getOrElse(offset + 6) { "" }
        val rrn        = allFields.getOrElse(offset + 7) { "" }
        val maskedPan  = allFields.getOrElse(offset + 8) { "" }
        val cardType   = allFields.getOrElse(offset + 9) { "" }

        return Response(command, resultCode, statusText, authCode, rrn, maskedPan, cardType,
                        allFields, body, lrcValid)
    }

    /** Extracts the POSLink command from raw framed bytes, skipping seqref if present. */
    fun extractCommand(bytes: ByteArray): String {
        val stxIdx = bytes.indexOfFirst { it == STX }
        val etxIdx = bytes.indexOfFirst { it == ETX }
        if (stxIdx < 0 || etxIdx <= stxIdx) return ""
        val fields = String(bytes, stxIdx + 1, etxIdx - stxIdx - 1, Charsets.US_ASCII).split(",")
        return if (fields[0] in KNOWN_COMMANDS) fields[0] else fields.getOrElse(1) { "" }
    }

    /** Extracts the display text from a DSP message sent by the terminal. */
    fun extractDspText(bytes: ByteArray): String {
        val stxIdx = bytes.indexOfFirst { it == STX }
        val etxIdx = bytes.indexOfFirst { it == ETX }
        if (stxIdx < 0 || etxIdx <= stxIdx) return ""
        val fields = String(bytes, stxIdx + 1, etxIdx - stxIdx - 1, Charsets.US_ASCII).split(",")
        val offset = if (fields[0] in KNOWN_COMMANDS) 0 else 1
        // Terminal DSP: [seqref,]DSP,idx,<text>,...
        return fields.getOrElse(offset + 2) { fields.getOrElse(offset + 1) { "" } }
    }

    // ── Debug display ────────────────────────────────────────────────────────

    fun prettyPrint(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when (b) {
                STX -> append("[STX]")
                ETX -> append("[ETX]")
                ACK -> append("[ACK]")
                NAK -> append("[NAK]")
                else -> if (v in 32..126) append(v.toChar()) else append("[%02X]".format(v))
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun dollars(amount: Double) = "%.2f".format(amount)
}

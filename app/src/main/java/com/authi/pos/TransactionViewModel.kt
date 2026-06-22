package com.authi.pos

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TransactionViewModel : ViewModel() {

    enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

    private val _logs = MutableLiveData<List<LogEntry>>(emptyList())
    val logs: LiveData<List<LogEntry>> = _logs

    private val _connectionStatus = MutableLiveData(ConnectionStatus.DISCONNECTED)
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    private val _isBusy = MutableLiveData(false)
    val isBusy: LiveData<Boolean> = _isBusy

    private val _lastInvoiceNo = MutableLiveData<String?>(null)
    val lastInvoiceNo: LiveData<String?> = _lastInvoiceNo

    private val _merchantIdxs = MutableLiveData<List<Int>>(listOf(1))
    val merchantIdxs: LiveData<List<Int>> = _merchantIdxs

    private val _isDiscoveringMerchants = MutableLiveData(false)
    val isDiscoveringMerchants: LiveData<Boolean> = _isDiscoveringMerchants

    private val socket = VerifoneSocketManager()
    private val logList = mutableListOf<LogEntry>()

    // Seqref from the last started PUR/REF — used by CANCEL to identify the active transaction
    private var currentSeqref: String = ""

    // ── Connection ──────────────────────────────────────────────────────────

    fun connect(host: String, port: Int) {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            log(LogLevel.INFO, "Connecting to $host:$port …")
            socket.connect(host, port)
                .onSuccess {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    log(LogLevel.SUCCESS, "Connected to $host:$port")
                    _isDiscoveringMerchants.postValue(true)
                    val idxs = getMerchantIdxs()
                    _merchantIdxs.postValue(idxs)
                    _isDiscoveringMerchants.postValue(false)
                    log(LogLevel.INFO, "Found ${idxs.size} merchant(s): $idxs")
                }
                .onFailure {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    log(LogLevel.ERROR, "Connection failed: ${it.message}")
                }
        }
    }

    fun disconnect() {
        socket.disconnect()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        log(LogLevel.INFO, "Disconnected")
    }

    // ── Transactions ────────────────────────────────────────────────────────

    fun sendSale(amountDollars: Double, merchantIdx: Int, seqref: String) = runTx {
        currentSeqref = seqref
        val msg = PosLinkProtocol.purchase(amountDollars, merchantIdx = merchantIdx, seqref = seqref)
        val modeNote = if (seqref.isBlank()) " (1-way — no TCP response)" else " [ref: $seqref]"
        log(LogLevel.SENT, "→ PURCHASE \$${"%.2f".format(amountDollars)} merchant=$merchantIdx$modeNote\n${PosLinkProtocol.prettyPrint(msg)}")

        val resp = socket.sendAndReceive(msg, 90_000) { statusText ->
            log(LogLevel.INFO, "Terminal: $statusText")
        }.getOrElse {
            log(LogLevel.ERROR, "Sale failed: ${it.message}"); return@runTx
        }
        if (resp.isEmpty()) {
            log(LogLevel.WARNING, "Transaction dispatched — no TCP response received. " +
                "Enter a Txn Ref to enable 2-way mode and get results in-app.")
            return@runTx
        }
        handleResponse(resp, "SALE")
    }

    fun sendRefund(amountDollars: Double, merchantIdx: Int, seqref: String) = runTx {
        currentSeqref = seqref
        val msg = PosLinkProtocol.refund(amountDollars, merchantIdx = merchantIdx, seqref = seqref)
        val modeNote = if (seqref.isBlank()) " (1-way)" else " [ref: $seqref]"
        log(LogLevel.SENT, "→ REFUND \$${"%.2f".format(amountDollars)} merchant=$merchantIdx$modeNote\n${PosLinkProtocol.prettyPrint(msg)}")

        val resp = socket.sendAndReceive(msg, 90_000) { statusText ->
            log(LogLevel.INFO, "Terminal: $statusText")
        }.getOrElse {
            log(LogLevel.ERROR, "Refund failed: ${it.message}"); return@runTx
        }
        if (resp.isEmpty()) {
            log(LogLevel.WARNING, "Refund dispatched — check terminal display for result.")
            return@runTx
        }
        handleResponse(resp, "REFUND")
    }

    fun sendCancel() {
        if (!socket.isConnected) { log(LogLevel.ERROR, "Not connected"); return }
        viewModelScope.launch {
            val msg = PosLinkProtocol.cancel(currentSeqref)
            log(LogLevel.SENT, "→ CANCEL [ref: $currentSeqref]\n${PosLinkProtocol.prettyPrint(msg)}")
            socket.sendAndReceive(msg, 10_000)
                .onSuccess { if (it.isNotEmpty()) log(LogLevel.RECEIVED, "← ${PosLinkProtocol.prettyPrint(it)}") }
                .onFailure { log(LogLevel.WARNING, "Cancel: ${it.message}") }
        }
    }

    fun sendSettlement() = runTx {
        val msg = PosLinkProtocol.settlement()
        log(LogLevel.SENT, "→ SETTLEMENT\n${PosLinkProtocol.prettyPrint(msg)}")

        val resp = socket.sendAndReceive(msg, 120_000).getOrElse {
            log(LogLevel.ERROR, "Settlement failed: ${it.message}"); return@runTx
        }
        if (resp.isEmpty()) {
            log(LogLevel.WARNING, "Settlement dispatched — check terminal for result.")
            return@runTx
        }
        handleResponse(resp, "SETTLEMENT")
    }

    fun sendTestTransaction() = runTx {
        val msg = PosLinkProtocol.testTransaction()
        log(LogLevel.SENT, "→ TEST TRANSACTION\n${PosLinkProtocol.prettyPrint(msg)}")

        val resp = socket.sendAndReceive(msg, 30_000).getOrElse {
            log(LogLevel.ERROR, "Test failed: ${it.message}"); return@runTx
        }
        if (resp.isNotEmpty()) handleResponse(resp, "TEST")
    }

    fun sendDisplayMessage(text: String) {
        if (!socket.isConnected) { log(LogLevel.ERROR, "Not connected"); return }
        viewModelScope.launch {
            val msg = PosLinkProtocol.displayMessage(text)
            log(LogLevel.SENT, "→ DISPLAY [$text]\n${PosLinkProtocol.prettyPrint(msg)}")
            socket.sendAndReceive(msg, 10_000)
                .onSuccess { if (it.isNotEmpty()) log(LogLevel.RECEIVED, "← ${PosLinkProtocol.prettyPrint(it)}") }
                .onFailure { log(LogLevel.ERROR, "Display failed: ${it.message}") }
        }
    }

    fun clearLogs() {
        logList.clear()
        _logs.value = emptyList()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun runTx(block: suspend () -> Unit) {
        if (!socket.isConnected) { log(LogLevel.ERROR, "Not connected to terminal"); return }
        if (_isBusy.value == true) { log(LogLevel.WARNING, "Terminal is busy"); return }
        viewModelScope.launch {
            _isBusy.postValue(true)
            block()
            _isBusy.postValue(false)
        }
    }

    private fun handleResponse(raw: ByteArray, txType: String) {
        log(LogLevel.RECEIVED, "← ${PosLinkProtocol.prettyPrint(raw)}")

        val resp = PosLinkProtocol.parseResponse(raw)
        if (!resp.lrcValid) log(LogLevel.WARNING, "⚠ LRC mismatch in response")

        if (resp.approved) {
            val detail = buildString {
                if (resp.authCode.isNotBlank()) append("Auth: ${resp.authCode}  ")
                if (resp.rrn.isNotBlank()) append("RRN: ${resp.rrn}  ")
                if (resp.maskedPan.isNotBlank()) append("Card: ${resp.maskedPan}  ")
                if (resp.cardType.isNotBlank()) append("(${resp.cardType})")
            }
            _lastInvoiceNo.postValue(resp.rrn.ifBlank { resp.authCode.ifBlank { null } })
            log(LogLevel.SUCCESS, "$txType APPROVED — ${resp.statusText}  $detail")
        } else {
            log(LogLevel.ERROR,
                "$txType DECLINED — ${resp.statusText.ifBlank { resp.resultCode }}  " +
                "Code: ${resp.resultCode}  Body: [${resp.rawBody}]")
        }
    }

    private suspend fun getMerchantIdxs(): List<Int> {
        val found = mutableListOf<Int>()
        for (i in 1..8) {
            var busyRetries = 0
            var advance = false
            while (!advance) {
                val msg = PosLinkProtocol.pollMerchant(i)
                log(LogLevel.SENT, "→ POL merchant $i\n${PosLinkProtocol.prettyPrint(msg)}")
                val result = socket.sendAndReceive(msg, 10_000)
                if (result.isFailure) {
                    log(LogLevel.WARNING, "Poll merchant $i: ${result.exceptionOrNull()?.message}")
                    return if (found.isEmpty()) listOf(1) else found
                }
                val raw = result.getOrThrow()
                if (raw.isEmpty()) return if (found.isEmpty()) listOf(1) else found
                val resp = PosLinkProtocol.parseResponse(raw)
                log(LogLevel.RECEIVED, "← ${PosLinkProtocol.prettyPrint(raw)}  [${resp.statusText}]")
                when {
                    resp.rawBody.contains("TERMINAL BUSY", ignoreCase = true) -> {
                        if (++busyRetries >= 5) {
                            log(LogLevel.WARNING, "Terminal still busy after retries, stopping merchant discovery")
                            return if (found.isEmpty()) listOf(1) else found
                        }
                        log(LogLevel.INFO, "Terminal busy, retrying in 1s… (attempt $busyRetries/5)")
                        delay(1_000)
                    }
                    resp.rawBody.contains("INVALID MERCHANT", ignoreCase = true) -> {
                        return if (found.isEmpty()) listOf(1) else found
                    }
                    else -> {
                        found.add(i)
                        advance = true
                    }
                }
            }
        }
        return if (found.isEmpty()) listOf(1) else found
    }

    private fun log(level: LogLevel, message: String) {
        logList.add(LogEntry(level, message))
        _logs.postValue(logList.toList())
    }

    override fun onCleared() {
        super.onCleared()
        socket.disconnect()
    }
}

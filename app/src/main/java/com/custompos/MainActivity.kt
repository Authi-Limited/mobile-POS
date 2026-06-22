package com.custompos

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.custompos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: TransactionViewModel by viewModels()
    private val logAdapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLog()
        setupButtons()
        observeViewModel()
        restoreConnectionPrefs()
    }

    // ── Setup ───────────────────────────────────────────────────────────────

    private fun setupLog() {
        binding.rvLogs.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            setHasFixedSize(false)
        }
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            val host = binding.etIpAddress.text.toString().trim()
            val port = binding.etPort.text.toString().toIntOrNull() ?: 5555
            if (host.isEmpty()) { toast("Enter IP address"); return@setOnClickListener }
            saveConnectionPrefs(host, port)
            vm.connect(host, port)
        }

        binding.btnDisconnect.setOnClickListener { vm.disconnect() }

        binding.btnSale.setOnClickListener {
            val amount = validatedAmount() ?: return@setOnClickListener
            vm.sendSale(
                amount,
                binding.etDescription.text.toString().trim(),
                binding.etSeqRef.text.toString().trim()
            )
        }

        binding.btnVoid.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cancel / Void")
                .setMessage("Send CANCEL to the terminal?\n\n" +
                    "This cancels the current in-progress transaction.\n" +
                    "Last RRN: ${vm.lastInvoiceNo.value ?: "none"}")
                .setPositiveButton("Cancel Transaction") { _, _ -> vm.sendCancel() }
                .setNegativeButton("Back", null)
                .show()
        }

        binding.btnRefund.setOnClickListener {
            val amount = validatedAmount() ?: return@setOnClickListener
            vm.sendRefund(
                amount,
                binding.etDescription.text.toString().trim(),
                binding.etSeqRef.text.toString().trim()
            )
        }

        binding.btnSettlement.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("End-of-Day Settlement")
                .setMessage("Run batch settlement for all pending transactions?")
                .setPositiveButton("Settle") { _, _ -> vm.sendSettlement() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCancel.setOnClickListener { vm.sendCancel() }

        binding.btnDisplayMsg.setOnClickListener {
            showInputDialog("Display on Terminal", "Message text", "Welcome!") { text ->
                if (text.isNotBlank()) vm.sendDisplayMessage(text)
            }
        }

        binding.btnGenerateRef.setOnClickListener {
            binding.etSeqRef.setText(System.currentTimeMillis().toString().takeLast(8))
        }

        binding.btnClearLogs.setOnClickListener { vm.clearLogs() }
    }

    // ── Observation ─────────────────────────────────────────────────────────

    private fun observeViewModel() {
        vm.logs.observe(this) { entries ->
            logAdapter.submitList(entries.toList()) {
                if (entries.isNotEmpty()) binding.rvLogs.scrollToPosition(entries.size - 1)
            }
        }

        vm.connectionStatus.observe(this) { status ->
            val (label, color, canConnect, canDisconnect, canTx) = when (status) {
                TransactionViewModel.ConnectionStatus.DISCONNECTED ->
                    StatusUi("● DISCONNECTED", R.color.status_disconnected, true, false, false)
                TransactionViewModel.ConnectionStatus.CONNECTING ->
                    StatusUi("● CONNECTING…",  R.color.status_connecting,  false, false, false)
                TransactionViewModel.ConnectionStatus.CONNECTED ->
                    StatusUi("● CONNECTED",    R.color.status_connected,   false, true,  true)
            }
            binding.tvConnectionStatus.text = label
            binding.tvConnectionStatus.setTextColor(getColor(color))
            binding.btnConnect.isEnabled = canConnect
            binding.btnDisconnect.isEnabled = canDisconnect
            setTxButtonsEnabled(canTx && vm.isBusy.value != true)
        }

        vm.isBusy.observe(this) { busy ->
            binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
            val connected = vm.connectionStatus.value == TransactionViewModel.ConnectionStatus.CONNECTED
            setTxButtonsEnabled(connected && !busy)
            binding.btnCancel.isEnabled = busy && connected
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun setTxButtonsEnabled(enabled: Boolean) {
        binding.btnSale.isEnabled = enabled
        binding.btnVoid.isEnabled = enabled
        binding.btnRefund.isEnabled = enabled
        binding.btnSettlement.isEnabled = enabled
        binding.btnDisplayMsg.isEnabled = enabled
    }

    private fun validatedAmount(): Double? {
        val text = binding.etAmount.text.toString().trim()
        if (text.isEmpty()) { toast("Enter an amount"); return null }
        val value = text.toDoubleOrNull()
        if (value == null || value <= 0) { toast("Enter a valid amount > 0"); return null }
        return value
    }

    private fun showInputDialog(
        title: String, hint: String, prefill: String = "",
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefill)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onConfirm(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConnectionPrefs(host: String, port: Int) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_IP, host)
            .putInt(KEY_PORT, port)
            .apply()
    }

    private fun restoreConnectionPrefs() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ip = prefs.getString(KEY_IP, "") ?: ""
        val port = prefs.getInt(KEY_PORT, 5555)
        if (ip.isNotEmpty()) binding.etIpAddress.setText(ip)
        binding.etPort.setText(port.toString())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private data class StatusUi(
        val label: String, val colorRes: Int,
        val canConnect: Boolean, val canDisconnect: Boolean, val canTx: Boolean
    )

    companion object {
        private const val PREFS = "pos_prefs"
        private const val KEY_IP = "last_ip"
        private const val KEY_PORT = "last_port"
    }
}

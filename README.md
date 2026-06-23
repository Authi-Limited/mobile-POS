# AUTHI Mobile POS

An Android POS application that communicates with a **Verifone V660p Plus-A (NZ)** payment terminal over TCP using the POSLink protocol.

## Overview

CustomPOS connects to a Verifone terminal over a local network and sends POSLink commands to trigger and manage payment transactions. It receives real-time status updates and final results back from the terminal, displaying everything in a live communication log.

## Features

- TCP connection to any Verifone terminal by IP and port
- Purchase, refund, cancel, and settlement commands
- 2-way mode: real-time terminal status ("AWAITING CARD", "PLEASE WAIT") during transactions
- Live communication log with sent/received raw bytes and pretty-printed frames
- Unique transaction ref (seqref) field with one-tap random generation
- SharedPreferences persistence for last-used IP and port
- Display message command for pushing text to the terminal screen

## Requirements

- Android 6.0+ (minSdk 23)
- Android Studio Hedgehog or newer
- Java 17
- Verifone V660p Plus-A terminal on the same network (POSLink TCP, default port 4444)

## Building

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run directly on a device or emulator.

## Project Structure

```
app/src/main/java/com/authi/pos/
├── MainActivity.kt          # UI, button wiring, SharedPreferences
├── TransactionViewModel.kt  # Business logic, coroutine lifecycle
├── VerifoneSocketManager.kt # TCP socket, POSLink frame send/receive loop
├── PosLinkProtocol.kt       # Command builders, framing, response parsing
├── LogAdapter.kt            # RecyclerView adapter for the log pane
└── LogEntry.kt              # Log entry model + timestamp formatting

app/src/main/res/
├── layout/activity_main.xml # Full UI layout
├── layout/item_log.xml      # Single log row layout
└── values/colors.xml        # Dark-theme colour palette
```

## POSLink Protocol

The terminal speaks a comma-delimited ASCII protocol framed with STX/ETX/LRC over TCP.

### Wire format

```
[STX 0x02][body ASCII][ETX 0x03][LRC]
```

LRC = XOR of all bytes from `body[0]` through `ETX` inclusive (STX excluded).

Handshake: after sending a message the terminal echoes `ACK (0x06)` or `NAK (0x15)`.

### 2-way vs 1-way mode

A message **without** a seqref prefix (e.g. `PUR,1,10.00,0.00,,YY,`) puts the terminal in **1-way mode** — it processes the transaction but sends no TCP response.

A message **with** a seqref prefix (e.g. `44440109,PUR,1,10.00,0.00,,YY,`) enables **2-way mode**: the terminal sends intermediate `DSP` status messages during the transaction and a final `PUR` result when complete.

The seqref must be **unique per transaction**. Reusing a seqref causes the terminal to return the cached result from the previous transaction.

### Command reference

| Command | Format | Notes |
|---------|--------|-------|
| PUR | `[ref,]PUR,<merchantIdx>,<amount>,<cashback>,<displayText>,<AllowCredit><ReturnReceipt>,` | e.g. `TX001,PUR,1,10.00,0.00,Coffee,YY,` |
| REF | `[ref,]REF,<merchantIdx>,<amount>,0.00,<displayText>,YY,` | Refund |
| CAN | `[ref,]CAN,` | Cancel in-progress transaction |
| SET | `SET,1,` | End-of-day settlement (no seqref) |
| POL | `POL,1,1,` | Poll / health-check (no seqref) |
| DSP | `DSP,<merchantIdx>,NFO,<text>,` | Display text on terminal screen (no seqref) |

### Response field layout (PUR/REF with seqref)

```
[0] seqref      [1] CMD      [2] txnType   [3] amount    [4] cashout
[5] resultCode  [6] status   [7] authCode  [8] RRN       [9] maskedPAN  [10] cardType
```

Result code `00` = approved. Status text `ACCEPTED` / `APPROVED` also indicates success.

### Intermediate DSP messages

During a 2-way transaction the terminal sends one or more status frames before the final response:

```
44440109,DSP,1,PLEASE WAIT...,
44440109,DSP,1,AWAITING CARD,
44440109,DSP,1,PLEASE WAIT...,   ← card presented
```

`VerifoneSocketManager` ACKs each DSP and continues reading until the final PUR/REF frame arrives.

## Terminal

Tested on: **Verifone V660p Plus-A (NZ)** — firmware 5.350.1, POSLinkNZ, POIID 444401090007.

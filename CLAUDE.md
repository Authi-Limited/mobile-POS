# CLAUDE.md — CustomPOS

## Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK
```

compileSdk/targetSdk 36 · minSdk 23 · Java 17 · Kotlin 1.9.22 · AGP 8.2.2

## Key source files

| File | Responsibility |
|------|---------------|
| `PosLinkProtocol.kt` | Command builders, STX/ETX/LRC framing, response parsing |
| `VerifoneSocketManager.kt` | TCP socket lifecycle, send/receive loop, DSP skip logic |
| `TransactionViewModel.kt` | Coroutine orchestration, log LiveData, seqref tracking |
| `MainActivity.kt` | View binding, button handlers, SharedPreferences |

## POSLink wire protocol

**Frame:** `[STX 0x02][ASCII body][ETX 0x03][LRC]`
LRC = XOR of every byte from `body[0]` through ETX inclusive. STX is excluded from LRC.

**Handshake:** Terminal → ACK (0x06) or NAK (0x15) after receiving our frame.

## Seqref (transaction reference)

Prepending a unique ID to a command enables **2-way mode**:
- `PUR,1,10.00,0.00,,YY,` → 1-way, terminal processes but sends no TCP response
- `TX001,PUR,1,10.00,0.00,,YY,` → 2-way, terminal sends intermediate DSP frames then a final result

**Critical:** reusing the same seqref returns the cached result from the previous transaction. The UI has a RND button that fills in `System.currentTimeMillis().toString().takeLast(8)`.

Only PUR, REF, CAN accept a seqref. POL, SET, DSP, TTX never use one.

## PUR command format (discovered via terminal logcat)

```
[seqref,]PUR,<merchantIdx>,<amount>,<cashback>,<displayText>,<AllowCredit><ReturnReceipt>,
```

Example: `D25310001,PUR,1,000010.00,000000.00,Test Text,YY,`

- `merchantIdx` — always `1` for this terminal
- `displayText` — shown on terminal screen during the transaction
- Flags: `Y/N` AllowCredit + `Y/N` ReturnReceipt (default `YY`)
- Amount format: `%.2f` (e.g. `10.00`) — terminal pads to 9 chars in its own logs but accepts unpadded

## Response field layout (2-way PUR/REF)

```
[0]=seqref  [1]=CMD  [2]=txnType  [3]=amount  [4]=cashout
[5]=resultCode(00=approved)  [6]=statusText  [7]=authCode  [8]=RRN
[9]=maskedPAN  [10]=cardType
```

`parseResponse()` detects a seqref by checking whether `fields[0]` is in `KNOWN_COMMANDS`. If not, offset shifts by 1.

## Intermediate DSP messages

During a 2-way transaction the terminal sends status frames before the final response:
```
44440109,DSP,1,PLEASE WAIT...,
44440109,DSP,1,AWAITING CARD,
```
`VerifoneSocketManager.sendAndReceive()` loops: ACKs each DSP → calls `onStatus` callback (logged as INFO) → keeps reading. Returns on the first non-DSP frame.

## Common gotchas

- **No seqref → no TCP response.** Without the seqref prefix the terminal uses 1-way mode and sends nothing back. The `soTimeout` fires and the app warns "no TCP response".
- **NAK means bad LRC.** If the LRC byte is wrong the terminal NAKs; `sendAndReceive()` throws so the ViewModel logs an error.
- **POL needs `POL,1,1,`** not `POL,` or `POL,1,`. The third field is the merchant index. Missing it returns "INVALID MERCHANT".
- **DSP format is `DSP,<idx>,NFO,<text>,`** not `DSP,<text>,`. "NFO" is the display type; missing it logs "Unhandled messagetype".
- **Seqref reuse returns cached result.** The terminal stores the last result per seqref and returns it if the same seqref is sent again. Always generate a fresh ref per transaction.
- **1-way mode on plain PUR** was previously misread as a timeout bug. It is not — the terminal processed the transaction and didn't respond. Add a seqref to get results in-app.

## Architecture

```
MainActivity
  └─ TransactionViewModel (ViewModel + coroutines)
       ├─ VerifoneSocketManager  (Dispatchers.IO, TCP)
       └─ PosLinkProtocol        (pure functions, no I/O)
```

All socket I/O runs on `Dispatchers.IO`. Results post back to `LiveData` observed on the main thread. `_isBusy` LiveData gates the transaction buttons and shows a progress bar.

## SharedPreferences

Key: `pos_prefs` — saves `last_ip` (String) and `last_port` (Int). Restored on `onCreate`.
The seqref / txn ref is intentionally **not** persisted — it must be unique per transaction.

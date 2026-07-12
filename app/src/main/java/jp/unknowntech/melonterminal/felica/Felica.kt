package jp.unknowntech.melonterminal.felica

import android.nfc.tech.NfcF
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * The minimal FeliCa (NFC-F / Type-3 Tag) layer the Melon terminal needs — adapted
 * from FeliCaDumper's `felica_standard` package, keeping only what a *relay* client
 * requires: Polling, Request System Code, and a raw frame relay. All cryptography
 * (mutual authentication) is driven by the melon server, not this app, so the
 * FeliCaDumper crypto/keystore is intentionally omitted.
 *
 * FeliCa frames are length-prefixed: `[LEN][command_code][idm…][params]`, where LEN
 * is the total length including itself. `NfcF.transceive()` expects and returns
 * such length-prefixed frames.
 */

/** A FeliCa protocol-level failure (unexpected response, truncation, …). */
class FelicaProtocolException(message: String) : IOException(message)

/** The card's IDm (manufacture ID) and PMm for a polled system. */
class PollingResult(val idm: ByteArray, val pmm: ByteArray)

/** Wildcard system code — polls the first system the card exposes. */
const val WILDCARD_SYSTEM_CODE: Int = 0xFFFF

private const val FS_POLLING_COMMAND_CODE = 0x00
private const val FS_POLLING_RESPONSE_CODE = 0x01
private const val FS_REQUEST_SYSTEM_CODE_COMMAND_CODE = 0x0C
private const val FS_REQUEST_SYSTEM_CODE_RESPONSE_CODE = 0x0D

// ----- byte helpers -----

private fun uByte(value: Byte): Int = value.toInt() and 0xFF

private fun beU16(bytes: ByteArray, offset: Int): Int =
    (uByte(bytes[offset]) shl 8) or uByte(bytes[offset + 1])

private fun writeBe16(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

private fun ensureMinLength(data: ByteArray, expected: Int, label: String) {
    if (data.size < expected) {
        throw FelicaProtocolException("$label (expected >= $expected bytes, got ${data.size})")
    }
}

/** Lowercase hex, no separators (matches the server's hex encoding). */
fun ByteArray.toHex(): String =
    joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }

/** Parse lowercase/uppercase hex into bytes; throws on odd length or bad digits. */
fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim()
    if (clean.length % 2 != 0) {
        throw FelicaProtocolException("hex string has odd length: ${clean.length}")
    }
    return ByteArray(clean.length / 2) { i ->
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) throw FelicaProtocolException("invalid hex in '$hex'")
        ((hi shl 4) or lo).toByte()
    }
}

// ----- framing / transceive -----

private fun frameWithLengthPrefix(payload: ByteArray): ByteArray {
    val frame = ByteArray(payload.size + 1)
    frame[0] = (payload.size + 1).toByte()
    payload.copyInto(frame, destinationOffset = 1)
    return frame
}

/** Trim a length-prefixed FeliCa response to its declared length. */
private fun trimToDeclaredLength(raw: ByteArray): ByteArray {
    ensureMinLength(raw, 2, "FeliCa response too short")
    val declared = uByte(raw[0])
    if (declared < 2) throw FelicaProtocolException("invalid FeliCa response length byte: $declared")
    if (declared > raw.size) {
        throw FelicaProtocolException("truncated FeliCa response: declared=$declared actual=${raw.size}")
    }
    return if (declared == raw.size) raw else raw.copyOf(declared)
}

/** Send a payload (WITHOUT a length byte); add the length prefix, transceive, and
 *  return the trimmed length-prefixed response. */
private fun transceive(nfcF: NfcF, payload: ByteArray): ByteArray =
    trimToDeclaredLength(nfcF.transceive(frameWithLengthPrefix(payload)))

private fun transceiveWithCode(
    nfcF: NfcF,
    payload: ByteArray,
    expectedResponseCode: Int,
    commandName: String,
): ByteArray {
    val response = transceive(nfcF, payload)
    val responseCode = uByte(response[1])
    if (responseCode != expectedResponseCode) {
        throw FelicaProtocolException(
            "$commandName unexpected response code: 0x%02X".format(responseCode)
        )
    }
    return response
}

// ----- client-side commands -----

/** Poll a system (use [WILDCARD_SYSTEM_CODE] to find the first one). Returns its
 *  IDm/PMm. Each system code has its OWN IDm, so re-poll after choosing a system. */
fun polling(nfcF: NfcF, systemCode: Int): PollingResult {
    val payload = ByteArrayOutputStream().apply {
        write(FS_POLLING_COMMAND_CODE)
        writeBe16(this, systemCode)
        write(0x00) // request code: none
        write(0x00) // time slots: 1
    }.toByteArray()
    val response = transceiveWithCode(nfcF, payload, FS_POLLING_RESPONSE_CODE, "Polling")
    ensureMinLength(response, 18, "Polling response too short")
    return PollingResult(
        idm = response.copyOfRange(2, 10),
        pmm = response.copyOfRange(10, 18),
    )
}

/** Ask the card which system codes it exposes (in card order), via Request System
 *  Code, using the IDm from a wildcard poll. */
fun requestSystemCode(nfcF: NfcF, idm: ByteArray): List<Int> {
    val payload = ByteArrayOutputStream().apply {
        write(FS_REQUEST_SYSTEM_CODE_COMMAND_CODE)
        write(idm)
    }.toByteArray()
    val response =
        transceiveWithCode(
            nfcF,
            payload,
            FS_REQUEST_SYSTEM_CODE_RESPONSE_CODE,
            "Request System Code"
        )
    ensureMinLength(response, 11, "Request System Code response too short")
    val count = uByte(response[10])
    if (count == 0) return emptyList()
    ensureMinLength(response, 11 + count * 2, "Request System Code list truncated")
    return List(count) { i -> beU16(response, 11 + i * 2) }
}

// ----- mutual-authentication relay -----

/**
 * Relay one mutual-authentication command frame from the server to the card and
 * return the card's response. The server sends the **full length-prefixed** frame,
 * which is exactly what `NfcF.transceive()` wants — so it is sent verbatim (no
 * length math). The returned response is trimmed to its declared length and posted
 * straight back to the server.
 */
fun relayFrame(nfcF: NfcF, frame: ByteArray): ByteArray =
    trimToDeclaredLength(nfcF.transceive(frame))

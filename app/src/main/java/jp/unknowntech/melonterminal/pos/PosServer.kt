package jp.unknowntech.melonterminal.pos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The POS-facing LAN endpoint: a UDP responder for discovery and a TCP server for
 * JSON commands. Both run on [Dispatchers.IO] coroutines; [stop] closes the sockets
 * (unblocking the accept/receive loops) and cancels the scope. Command handling is
 * delegated to a [PosCommandHandler] — this class only frames, parses, and routes.
 *
 * A [WifiManager.MulticastLock] is held while running so broadcast discovery packets
 * are delivered on devices that would otherwise filter them.
 */
class PosServer(
    context: Context,
    private val handler: PosCommandHandler,
) {
    private val appContext = context.applicationContext
    private val multicastLock =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("melon-pos")
            ?.apply { setReferenceCounted(false) }

    private var scope: CoroutineScope? = null
    private var udp: DatagramSocket? = null
    private var tcp: ServerSocket? = null

    /** "ip:port" the command server is reachable at, for display; null until started. */
    @Volatile
    var address: String? = null
        private set

    val isRunning: Boolean get() = scope != null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        runCatching { multicastLock?.acquire() }
        val ip = localIpv4()
        address = "${ip ?: "?"}:${PosProtocol.COMMAND_PORT}"
        s.launch { runDiscovery() }
        s.launch { runCommands() }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { udp?.close() }
        runCatching { tcp?.close() }
        udp = null
        tcp = null
        address = null
        runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
    }

    // ----- UDP discovery -----

    private fun runDiscovery() {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PosProtocol.DISCOVERY_PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "discovery bind failed", e)
            return
        }
        udp = socket
        val buf = ByteArray(2048)
        while (scope?.isActive == true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                break // socket closed by stop()
            }
            val line = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
            val msg = parseEnvelope(line) ?: continue
            if (msg["command"]?.jsonPrimitive?.contentOrNull != "discover") continue
            val reply = handler.announce(localIpv4())
            val out = wrap(PosProtocol.json.encodeToString(AnnounceMsg.serializer(), reply))
            runCatching {
                socket.send(DatagramPacket(out, out.size, packet.address, packet.port))
            }
        }
    }

    // ----- TCP commands -----

    private fun runCommands() {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PosProtocol.COMMAND_PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "command bind failed", e)
            return
        }
        tcp = server
        while (scope?.isActive == true) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break // socket closed by stop()
            }
            scope?.launch { serveClient(client) }
        }
    }

    /** One connection: read newline-delimited request lines, reply to each in turn. */
    private fun serveClient(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.UTF_8))
            val output = it.getOutputStream()
            while (scope?.isActive == true) {
                val line = try {
                    reader.readLine() ?: break
                } catch (_: IOException) {
                    break
                }
                if (line.isBlank()) continue
                val reply = dispatch(line)
                try {
                    output.write(encodeReply(reply))
                    output.flush()
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    /** Parse one request line and route it to the handler. */
    private fun dispatch(line: String): PosReply {
        val envelope = runCatching {
            PosProtocol.json.decodeFromString(Envelope.serializer(), line)
        }.getOrNull()
            ?: return PosReply.Error(ErrorMsg(code = PosError.BAD_REQUEST, message = "invalid JSON envelope"))

        if (envelope.melon_pos != PosProtocol.VERSION) {
            return PosReply.Error(
                ErrorMsg(
                    code = PosError.UNSUPPORTED_VERSION,
                    message = "protocol ${envelope.melon_pos} not supported"
                )
            )
        }
        if (envelope.alg != PosProtocol.ALG_NONE) {
            return PosReply.Error(
                ErrorMsg(
                    code = PosError.UNSUPPORTED_ALG,
                    message = "alg '${envelope.alg}' not supported"
                )
            )
        }

        val msg = envelope.msg
        val command = msg["command"]?.jsonPrimitive?.contentOrNull
            ?: return PosReply.Error(ErrorMsg(code = PosError.BAD_REQUEST, message = "missing command"))
        val requestId = msg.str("request_id")

        return when (command) {
            "info" -> handler.handleInfo()
            "status" -> handler.handleStatus()
            "cancel" -> handler.handleCancel(requestId)
            "payment" -> {
                val amount = msg["amount"]?.jsonPrimitive?.longOrNull
                    ?: return badRequest("payment requires a numeric amount")
                handler.handlePayment(requestId, amount, msg.str("note"))
            }

            "topup" -> {
                val amount = msg["amount"]?.jsonPrimitive?.longOrNull
                    ?: return badRequest("topup requires a numeric amount")
                handler.handleTopup(requestId, amount)
            }

            "balance" -> handler.handleBalance(requestId)
            "refund_query" -> handler.handleRefundQuery(requestId)
            "refund_execute" -> {
                val paymentId = msg.str("payment_id")
                    ?: return badRequest("refund_execute requires payment_id")
                handler.handleRefundExecute(requestId, paymentId, msg["amount"]?.jsonPrimitive?.longOrNull)
            }

            else -> PosReply.Error(ErrorMsg(code = PosError.UNKNOWN_COMMAND, message = "unknown command '$command'"))
        }
    }

    // ----- framing helpers -----

    private fun encodeReply(reply: PosReply): ByteArray {
        val body = when (reply) {
            is PosReply.Status -> PosProtocol.json.encodeToString(StatusMsg.serializer(), reply.msg)
            is PosReply.Info -> PosProtocol.json.encodeToString(InfoMsg.serializer(), reply.msg)
            is PosReply.Announce -> PosProtocol.json.encodeToString(AnnounceMsg.serializer(), reply.msg)
            is PosReply.Error -> PosProtocol.json.encodeToString(ErrorMsg.serializer(), reply.msg)
        }
        return wrap(body)
    }

    /** Wrap a serialized message object into an envelope line (newline-terminated). */
    private fun wrap(bodyJson: String): ByteArray {
        val line = "{\"melon_pos\":${PosProtocol.VERSION},\"alg\":\"${PosProtocol.ALG_NONE}\",\"msg\":$bodyJson}\n"
        return line.toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseEnvelope(line: String): JsonObject? =
        runCatching { PosProtocol.json.decodeFromString(Envelope.serializer(), line).msg }.getOrNull()

    private fun badRequest(message: String): PosReply =
        PosReply.Error(ErrorMsg(code = PosError.BAD_REQUEST, message = message))

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private const val TAG = "PosServer"

        /** First site-local IPv4 on an up, non-loopback interface (the Wi-Fi/LAN address). */
        fun localIpv4(): String? =
            runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
    }
}

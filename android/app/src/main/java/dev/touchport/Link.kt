package dev.touchport

import android.os.Process
import android.os.SystemClock
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Sends the controller state to touchport-receiver on the laptop.
 *
 * Every packet holds the full state (held keys + cumulative mouse counters), so the receiver
 * never misses a key release or mouse motion when a UDP packet is lost. The sender thread
 * sends at once when the state changes (see [kick]) and every [KEEPALIVE_NS] otherwise.
 * Packet layout is documented in receiver/touchport-receiver.c.
 */
class Link(private val settings: Settings) {
    @Volatile
    var status = "Starting…"
        private set
    var onStatus: (() -> Unit)? = null

    private val lock = Any()
    private var buttons = 0L
    private var mx = 0
    private var my = 0
    private var wheel = 0
    private var fracX = 0f
    private var fracY = 0f

    @Volatile private var running = false
    @Volatile private var reconnectWanted = false
    @Volatile private var lastPong = 0L
    @Volatile private var thread: Thread? = null

    fun setButtons(b: Long) {
        synchronized(lock) { buttons = b }
        kick()
    }

    fun addMouse(dx: Float, dy: Float) {
        synchronized(lock) {
            fracX += dx
            fracY += dy
            val ix = fracX.toInt()
            val iy = fracY.toInt()
            fracX -= ix
            fracY -= iy
            mx += ix
            my += iy
        }
        kick()
    }

    fun addWheel(n: Int) {
        synchronized(lock) { wheel += n }
        kick()
    }

    fun kick() {
        thread?.let(LockSupport::unpark)
    }

    fun start() {
        if (running) return
        running = true
        thread = thread(name = "tp-link") { run() }
    }

    fun stop() {
        running = false
        val t = thread ?: return
        LockSupport.unpark(t)
        t.join(1000)
        thread = null
    }

    fun reconnect() {
        reconnectWanted = true
        kick()
    }

    private fun setStatus(s: String) {
        if (s == status) return
        status = s
        onStatus?.invoke()
    }

    private fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {
        }
        val session = Random.nextInt() or 1
        val out = ByteBuffer.allocate(PKT).order(ByteOrder.LITTLE_ENDIAN)
        var t: Transport? = null
        var seq = 0
        var nextPing = 0L
        var nextUsbCheck = 0L

        while (running) {
            if (reconnectWanted || t?.dead == true) {
                reconnectWanted = false
                t?.close()
                t = null
            }
            if (t == null) {
                t = try {
                    open()
                } catch (e: Exception) {
                    null
                }
                if (t == null) {
                    setStatus(waitingText())
                    LockSupport.parkNanos(RETRY_NS)
                    continue
                }
                startReader(t)
                lastPong = System.nanoTime()
                nextPing = 0
                setStatus(t.label)
            }

            val now = System.nanoTime()
            try {
                synchronized(lock) { fill(out, T_STATE, ++seq, buttons, mx, my, wheel, session) }
                t.send(out)
                if (now >= nextPing) {
                    fill(out, T_PING, 0, now, 0, 0, 0, session)
                    t.send(out)
                    nextPing = now + PING_NS
                }
            } catch (e: IOException) {
                t.dead = true
                setStatus("Connection lost, retrying…")
                continue
            }
            if (now - lastPong > SILENT_NS) {
                t.dead = true
                setStatus("Laptop not answering, retrying…")
                continue
            }
            // Cable plugged in while on Wi-Fi: switch to USB.
            if (settings.linkMode == LinkMode.AUTO && !t.usb && now >= nextUsbCheck) {
                nextUsbCheck = now + USB_CHECK_NS
                val adb = tryAdb()
                if (adb != null) {
                    t.close()
                    t = adb
                    startReader(adb)
                    lastPong = System.nanoTime()
                    nextPing = 0
                    setStatus(adb.label)
                    continue
                }
                if (usbTetherUp()) reconnectWanted = true
            }
            LockSupport.parkNanos(KEEPALIVE_NS)
        }

        t?.let {
            try {
                synchronized(lock) { fill(out, T_BYE, ++seq, 0, mx, my, wheel, session) }
                it.send(out)
            } catch (_: IOException) {
            }
            it.close()
        }
    }

    private fun startReader(t: Transport) = thread(name = "tp-rx", isDaemon = true) {
        val b = ByteBuffer.allocate(REPLY).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (!t.dead) {
                if (!t.receive(b)) continue
                if (b.get(0) == 'T'.code.toByte() && b.get(3) == T_PONG) {
                    val now = System.nanoTime()
                    lastPong = now
                    setStatus("%s · ping %.1f ms".format(t.label, (now - b.getLong(8)) / 1e6))
                }
            }
        } catch (_: Exception) {
        }
        t.dead = true
        kick()
    }

    private fun waitingText() = when (settings.linkMode) {
        LinkMode.AUTO -> "Looking for laptop… run touchport-receiver (USB or Wi-Fi)"
        LinkMode.ADB -> "Waiting for USB (ADB)… plug in and run touchport-receiver"
        LinkMode.MANUAL -> "Cannot reach ${settings.manualIp.ifBlank { "(no IP set)" }}"
    }

    private fun open(): Transport? = when (settings.linkMode) {
        LinkMode.ADB -> TcpTransport()
        LinkMode.MANUAL -> {
            val ip = settings.manualIp.trim()
            if (ip.isEmpty()) null else {
                val addr = InetAddress.getByName(ip)
                UdpTransport(addr, "$ip", interfaceFor(addr, upInterfaces())?.let(::isUsb) ?: false)
            }
        }
        LinkMode.AUTO -> tryAdb() ?: discover()
    }

    /** USB through ADB works whenever the cable is in and the receiver has set up "adb reverse". */
    private fun tryAdb(): Transport? = try {
        TcpTransport()
    } catch (_: IOException) {
        null
    }

    /** Broadcasts a discovery packet on every network and picks the best reply (USB first). */
    private fun discover(): Transport? {
        val ifaces = upInterfaces()
        val targets = LinkedHashSet<InetAddress>()
        for (ni in ifaces) for (ia in ni.interfaceAddresses) ia.broadcast?.let(targets::add)
        targets.add(InetAddress.getByName("255.255.255.255"))

        val req = ByteBuffer.allocate(PKT).order(ByteOrder.LITTLE_ENDIAN)
        fill(req, T_DISCOVER, 0, 0, 0, 0, 0, 0)
        val replies = mutableListOf<Pair<InetAddress, String>>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 100
            val buf = ByteArray(REPLY)
            val pkt = DatagramPacket(buf, buf.size)
            repeat(2) {
                for (target in targets) {
                    try {
                        s.send(DatagramPacket(req.array(), PKT, target, PORT))
                    } catch (_: IOException) {
                    }
                }
                val end = SystemClock.elapsedRealtime() + 250
                while (SystemClock.elapsedRealtime() < end) {
                    try {
                        pkt.length = buf.size
                        s.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (pkt.length >= REPLY && buf[0] == 'T'.code.toByte() && buf[1] == 'P'.code.toByte() &&
                        buf[3] == T_DISCOVER_REPLY
                    ) {
                        val host = String(buf, 32, 31, Charsets.UTF_8).substringBefore('\u0000')
                        if (replies.none { it.first == pkt.address }) replies += pkt.address to host
                    }
                }
                if (replies.isNotEmpty()) {
                    val ranked = replies.map { (addr, host) ->
                        Triple(addr, host, interfaceFor(addr, ifaces)?.let(::isUsb) ?: false)
                    }.sortedByDescending { it.third }
                    val (addr, host, usb) = ranked.first()
                    return UdpTransport(addr, "${if (usb) "USB" else "Wi-Fi"} · $host", usb)
                }
            }
        }
        return null
    }

    private fun upInterfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter {
            try {
                it.isUp && !it.isLoopback
            } catch (_: Exception) {
                false
            }
        }

    private fun usbTetherUp() = upInterfaces().any { ni ->
        isUsb(ni) && ni.interfaceAddresses.any { it.address is Inet4Address }
    }

    private fun isUsb(ni: NetworkInterface) = USB_NAMES.any { ni.name.startsWith(it) }

    private fun interfaceFor(addr: InetAddress, ifaces: List<NetworkInterface>) =
        ifaces.firstOrNull { ni -> ni.interfaceAddresses.any { sameSubnet(it, addr) } }

    private fun sameSubnet(ia: InterfaceAddress, addr: InetAddress): Boolean {
        val a = ia.address as? Inet4Address ?: return false
        if (addr !is Inet4Address) return false
        val bits = ia.networkPrefixLength.toInt()
        if (bits !in 1..32) return false
        val mask = if (bits == 32) -1 else (-1 shl (32 - bits))
        return (toInt(a) and mask) == (toInt(addr) and mask)
    }

    private fun toInt(a: Inet4Address) = ByteBuffer.wrap(a.address).int

    private fun fill(b: ByteBuffer, type: Byte, seq: Int, buttons: Long, mx: Int, my: Int, wheel: Int, session: Int) {
        b.clear()
        b.put('T'.code.toByte()).put('P'.code.toByte()).put(VERSION).put(type)
        b.putInt(seq).putLong(buttons).putInt(mx).putInt(my).putInt(wheel).putInt(session)
        b.flip()
    }

    private abstract class Transport(val label: String, val usb: Boolean) {
        @Volatile var dead = false
        abstract fun send(b: ByteBuffer)
        /** Blocks until a packet arrives. */
        abstract fun receive(b: ByteBuffer): Boolean
        abstract fun close()
    }

    private class UdpTransport(addr: InetAddress, label: String, usb: Boolean) : Transport(label, usb) {
        private val ch = DatagramChannel.open().apply {
            try {
                setOption(StandardSocketOptions.IP_TOS, 0xB8) // DSCP EF: low-latency Wi-Fi queue
            } catch (_: Exception) {
            }
            connect(InetSocketAddress(addr, PORT))
        }

        override fun send(b: ByteBuffer) {
            b.rewind()
            ch.write(b)
        }

        override fun receive(b: ByteBuffer): Boolean {
            b.clear()
            return ch.read(b) >= PKT
        }

        override fun close() {
            dead = true
            try {
                ch.close()
            } catch (_: IOException) {
            }
        }
    }

    /** USB through "adb reverse tcp:47800 tcp:47800", which the receiver sets up. */
    private class TcpTransport : Transport("USB (ADB)", true) {
        private val ch = SocketChannel.open().apply {
            socket().tcpNoDelay = true
            socket().connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 500)
        }

        override fun send(b: ByteBuffer) {
            b.rewind()
            while (b.hasRemaining()) ch.write(b)
        }

        override fun receive(b: ByteBuffer): Boolean {
            b.clear()
            b.limit(PKT)
            while (b.hasRemaining()) if (ch.read(b) < 0) throw EOFException()
            return true
        }

        override fun close() {
            dead = true
            try {
                ch.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val PORT = 47800
        const val PKT = 32
        const val REPLY = 64
        const val VERSION: Byte = 1
        const val T_STATE: Byte = 1
        const val T_DISCOVER: Byte = 2
        const val T_DISCOVER_REPLY: Byte = 3
        const val T_BYE: Byte = 4
        const val T_PING: Byte = 5
        const val T_PONG: Byte = 6

        const val KEEPALIVE_NS = 50_000_000L
        const val PING_NS = 1_000_000_000L
        const val SILENT_NS = 3_000_000_000L
        const val RETRY_NS = 1_000_000_000L
        const val USB_CHECK_NS = 2_000_000_000L

        // Android names for USB tethering and USB Ethernet interfaces.
        val USB_NAMES = listOf("rndis", "ncm", "usb", "eth")
    }
}

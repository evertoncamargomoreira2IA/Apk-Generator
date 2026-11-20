package com.voxlink.ai.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

enum class SyncTransport(val label: String) {
    WIFI("Wi-Fi local / hotspot"),
    WIFI_DIRECT("Wi-Fi Direct"),
    BLUETOOTH("Bluetooth"),
}

enum class SyncState { IDLE, WAITING, CONNECTING, CONNECTED }

data class Peer(val id: String, val name: String, val transport: SyncTransport)

/**
 * Links two VoxLink phones (no internet, no server, no cost). Messages are one JSON object per line
 * over a TCP socket (Wi-Fi local, Wi-Fi Direct) or an RFCOMM socket (Bluetooth).
 * Callbacks always arrive on the main thread.
 */
@SuppressLint("MissingPermission")
class SyncManager(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onSyncState(state: SyncState, info: String)
        fun onPeers(peers: List<Peer>)
        fun onSyncMessage(msg: JSONObject)
    }

    companion object {
        const val PORT = 47800
        const val SERVICE_TYPE = "_voxlink._tcp."
        val BT_UUID: UUID = UUID.fromString("7f3e1a52-2c4b-4d0e-9a51-6b3c8d2e4f10")
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val sender = Executors.newSingleThreadExecutor()

    @Volatile private var out: OutputStream? = null
    @Volatile private var closer: (() -> Unit)? = null
    @Volatile private var stopping = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var btServer: BluetoothServerSocket? = null

    var state = SyncState.IDLE; private set
    var transport: SyncTransport? = null; private set
    var myName: String = Build.MODEL ?: "VoxLink"

    private val peers = LinkedHashMap<String, Peer>()

    // ------------------------------------------------------------ common

    private fun setState(s: SyncState, info: String) {
        state = s
        main.post { listener.onSyncState(s, info) }
    }

    private fun publishPeers() {
        val list = peers.values.toList()
        main.post { listener.onPeers(list) }
    }

    private fun fail(msg: String) {
        if (stopping) return
        cleanup()
        setState(SyncState.IDLE, msg)
    }

    private fun attach(input: java.io.InputStream, output: OutputStream, close: () -> Unit, peerLabel: String) {
        out = output
        closer = close
        closeServers()
        setState(SyncState.CONNECTED, peerLabel)
        io.execute {
            try {
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    main.post { listener.onSyncMessage(msg) }
                }
            } catch (_: Exception) {
            }
            if (!stopping) fail("Conexão encerrada pelo outro celular")
        }
    }

    fun send(msg: JSONObject) {
        val o = out ?: return
        sender.execute {
            try {
                o.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (_: Exception) {
                fail("Falha ao enviar — conexão perdida")
            }
        }
    }

    private fun closeServers() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { btServer?.close() }
        btServer = null
        stopNsd()
    }

    private fun cleanup() {
        runCatching { closer?.invoke() }
        closer = null
        out = null
        closeServers()
        stopWifiDirect()
    }

    fun stop() {
        stopping = true
        if (state == SyncState.CONNECTED) runCatching { send(JSONObject().put("t", "bye")) }
        cleanup()
        peers.clear()
        publishPeers()
        transport = null
        setState(SyncState.IDLE, "Desconectado")
        stopping = false
    }

    private fun begin(t: SyncTransport) {
        stopping = true
        cleanup()
        stopping = false
        peers.clear()
        publishPeers()
        transport = t
    }

    // ------------------------------------------------------------ Wi-Fi local (same network or hotspot)

    fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    }.getOrNull() ?: "?"

    /** This phone waits; the other one finds it automatically or types the IP shown. */
    fun hostWifi() {
        begin(SyncTransport.WIFI)
        setState(SyncState.WAITING, "Aguardando no IP ${localIp()} (porta $PORT)")
        io.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                serverSocket = ss
                main.post { registerNsd() }
                val s = ss.accept()
                s.tcpNoDelay = true
                attach(s.getInputStream(), s.getOutputStream(), { s.close() }, s.inetAddress.hostAddress ?: "Wi-Fi")
            } catch (e: Exception) {
                fail("Wi-Fi: ${e.message ?: "erro ao aguardar conexão"}")
            }
        }
    }

    /** Looks for phones that are waiting on the same network. */
    fun discoverWifi() {
        begin(SyncTransport.WIFI)
        setState(SyncState.WAITING, "Procurando celulares na mesma rede…")
        startNsdDiscovery()
    }

    fun joinWifi(host: String) {
        stopNsd()
        transport = SyncTransport.WIFI
        setState(SyncState.CONNECTING, "Conectando a $host…")
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host.trim(), PORT), 7000)
                s.tcpNoDelay = true
                attach(s.getInputStream(), s.getOutputStream(), { s.close() }, host)
            } catch (e: Exception) {
                fail("Não conectou em $host: ${e.message ?: "erro"}. Os dois estão na mesma rede?")
            }
        }
    }

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun nsdManager(): NsdManager = nsd ?: (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).also { nsd = it }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = "VoxLink-$myName"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        runCatching { nsdManager().registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun startNsdDiscovery() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) = fail("Busca na rede falhou ($e). Digite o IP manualmente.")
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("VoxLink") || info.serviceName == "VoxLink-$myName") return
                main.post {
                    resolveQueue.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    peers.entries.removeAll { it.value.name == info.serviceName.removePrefix("VoxLink-") }
                    publishPeers()
                }
            }
        }
        discListener = l
        runCatching { nsdManager().discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { fail("Busca na rede indisponível. Digite o IP manualmente.") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            nsdManager().resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null) {
                            peers[host] = Peer(host, i.serviceName.removePrefix("VoxLink-"), SyncTransport.WIFI)
                            publishPeers()
                        }
                        resolveNext()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    private fun stopNsd() {
        val m = nsd ?: return
        regListener?.let { runCatching { m.unregisterService(it) } }
        regListener = null
        discListener?.let { runCatching { m.stopServiceDiscovery(it) } }
        discListener = null
        resolveQueue.clear()
        resolving = false
    }

    // ------------------------------------------------------------ Wi-Fi Direct (phone-to-phone, no router)

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    @Volatile private var p2pSocketStarted = false

    fun startWifiDirect() {
        begin(SyncTransport.WIFI_DIRECT)
        val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            fail("Este celular não tem Wi-Fi Direct")
            return
        }
        p2p = m
        val ch = m.initialize(ctx, Looper.getMainLooper(), null)
        channel = ch
        p2pSocketStarted = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> m.requestPeers(ch) { list ->
                        peers.clear()
                        list.deviceList.forEach { d ->
                            peers[d.deviceAddress] = Peer(d.deviceAddress, d.deviceName.ifBlank { d.deviceAddress }, SyncTransport.WIFI_DIRECT)
                        }
                        publishPeers()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> m.requestConnectionInfo(ch) { info ->
                        if (info != null && info.groupFormed && !p2pSocketStarted && state != SyncState.CONNECTED) {
                            p2pSocketStarted = true
                            if (info.isGroupOwner) p2pServe() else p2pConnect(info.groupOwnerAddress?.hostAddress ?: "")
                        }
                    }
                }
            }
        }
        p2pReceiver = r
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = setState(SyncState.WAITING, "Procurando celulares por Wi-Fi Direct…")
            override fun onFailure(reason: Int) =
                fail("Wi-Fi Direct não iniciou ($reason). Ligue o Wi-Fi (e a localização no Android ≤ 12).")
        })
    }

    fun connectWifiDirect(peer: Peer) {
        val m = p2p ?: return
        val ch = channel ?: return
        val cfg = WifiP2pConfig().apply {
            deviceAddress = peer.id
            @Suppress("DEPRECATION")
            wps.setup = WpsInfo.PBC
        }
        setState(SyncState.CONNECTING, "Conectando a ${peer.name}… aceite o convite no outro celular")
        m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) = fail("Convite Wi-Fi Direct falhou ($reason)")
        })
    }

    private fun p2pServe() {
        setState(SyncState.CONNECTING, "Grupo Wi-Fi Direct formado, aguardando…")
        io.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                serverSocket = ss
                val s = ss.accept()
                s.tcpNoDelay = true
                attach(s.getInputStream(), s.getOutputStream(), { s.close() }, "Wi-Fi Direct")
            } catch (e: Exception) {
                fail("Wi-Fi Direct: ${e.message}")
            }
        }
    }

    private fun p2pConnect(host: String) {
        setState(SyncState.CONNECTING, "Grupo Wi-Fi Direct formado, conectando…")
        io.execute {
            repeat(12) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, PORT), 3000)
                    s.tcpNoDelay = true
                    attach(s.getInputStream(), s.getOutputStream(), { s.close() }, "Wi-Fi Direct")
                    return@execute
                } catch (_: Exception) {
                    Thread.sleep(700)
                }
            }
            fail("Wi-Fi Direct: não foi possível abrir a conexão")
        }
    }

    private fun stopWifiDirect() {
        val m = p2p ?: return
        val ch = channel
        p2pReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        p2pReceiver = null
        if (ch != null) {
            runCatching { m.stopPeerDiscovery(ch, null) }
            runCatching { m.removeGroup(ch, null) }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
        channel = null
        p2p = null
        p2pSocketStarted = false
    }

    // ------------------------------------------------------------ Bluetooth (paired phones)

    private fun adapter(): BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hostBluetooth() {
        begin(SyncTransport.BLUETOOTH)
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            fail("Ligue o Bluetooth")
            return
        }
        setState(SyncState.WAITING, "Aguardando conexão Bluetooth em \"${runCatching { ad.name }.getOrNull() ?: myName}\"…")
        io.execute {
            try {
                val ss = ad.listenUsingInsecureRfcommWithServiceRecord("VoxLink", BT_UUID)
                btServer = ss
                val s = ss.accept()
                attach(s.inputStream, s.outputStream, { s.close() }, runCatching { s.remoteDevice.name }.getOrNull() ?: "Bluetooth")
            } catch (e: Exception) {
                fail("Bluetooth: ${e.message ?: "erro ao aguardar"}")
            }
        }
    }

    /** Paired (bonded) phones — pair them once in the Android Bluetooth settings. */
    fun listBluetooth() {
        begin(SyncTransport.BLUETOOTH)
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            fail("Ligue o Bluetooth")
            return
        }
        runCatching { ad.bondedDevices }.getOrNull().orEmpty().forEach { d ->
            peers[d.address] = Peer(d.address, runCatching { d.name }.getOrNull() ?: d.address, SyncTransport.BLUETOOTH)
        }
        publishPeers()
        setState(SyncState.WAITING, if (peers.isEmpty()) "Nenhum aparelho pareado. Pareie nas configurações de Bluetooth." else "Escolha o outro celular")
    }

    fun joinBluetooth(peer: Peer) {
        val ad = adapter() ?: return fail("Bluetooth indisponível")
        setState(SyncState.CONNECTING, "Conectando a ${peer.name} por Bluetooth…")
        io.execute {
            try {
                runCatching { ad.cancelDiscovery() }
                val s = ad.getRemoteDevice(peer.id).createInsecureRfcommSocketToServiceRecord(BT_UUID)
                s.connect()
                attach(s.inputStream, s.outputStream, { s.close() }, peer.name)
            } catch (e: Exception) {
                fail("Bluetooth: não conectou em ${peer.name}. O outro celular está em \"Aguardar\"?")
            }
        }
    }

    fun shutdown() {
        stop()
        io.shutdownNow()
        sender.shutdownNow()
    }
}

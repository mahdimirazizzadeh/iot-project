package com.example.megacontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** یک کانال ارتباطی خط‌به‌خط (متنی) با آردوینو */
interface Link {
    suspend fun open()
    fun write(line: String)
    suspend fun readLines(onLine: (String) -> Unit)
    fun close()
}

abstract class StreamLink : Link {
    protected var input: InputStream? = null
    protected var output: OutputStream? = null

    override fun write(line: String) {
        try {
            output?.write("$line\n".toByteArray())
            output?.flush()
        } catch (_: IOException) {
        }
    }

    override suspend fun readLines(onLine: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val reader = input!!.bufferedReader()
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isNotBlank()) onLine(l.trim())
            }
        }
    }
}

/** بلوتوث کلاسیک (SPP) به ماژول HC-05 */
class BtLink(private val device: BluetoothDevice) : StreamLink() {
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val s = device.createRfcommSocketToServiceRecord(SPP)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
        }
    }

    override fun close() {
        runCatching { socket?.close() }
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** TCP روی WiFi به ESP8266 (حالت Access Point: معمولاً 192.168.4.1:8080) */
class WifiLink(
    private val ctx: Context,
    private val host: String,
    private val port: Int
) : StreamLink() {
    private var socket: Socket? = null

    @Suppress("DEPRECATION")
    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: throw IOException("شبکه WiFi فعال پیدا نشد")
            cm.bindProcessToNetwork(wifi)   // کل ترافیک اپ از مسیر WiFi برود
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 6000)
            s.tcpNoDelay = true
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching {
            ctx.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        }
    }
}
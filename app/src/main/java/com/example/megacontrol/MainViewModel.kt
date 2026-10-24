package com.example.megacontrol

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LinkMode { BLUETOOTH, WIFI }
enum class LinkState { IDLE, CONNECTING, CONNECTED }

data class Telemetry(
    val temp: Float? = null,
    val hum: Float? = null,
    val co: Float? = null,
    val dist: Float? = null,
    val relays: List<Boolean> = List(4) { false }
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var mode by mutableStateOf(LinkMode.BLUETOOTH)
    var state by mutableStateOf(LinkState.IDLE)
    var error by mutableStateOf<String?>(null)
    var data by mutableStateOf(Telemetry())

    var host by mutableStateOf("192.168.4.1")
    var port by mutableStateOf("8080")

    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    var selected by mutableStateOf<BluetoothDevice?>(null)

    private var link: Link? = null
    private var job: Job? = null
    private var stopping = false

    @SuppressLint("MissingPermission")
    fun loadPaired() {
        val adapter = getApplication<Application>()
            .getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.toList().orEmpty()
        if (selected == null) {
            selected = devices.firstOrNull { it.name?.contains("HC", true) == true } ?: devices.firstOrNull()
        }
    }

    fun connect() {
        if (state != LinkState.IDLE) return
        error = null
        stopping = false
        state = LinkState.CONNECTING
        job = viewModelScope.launch {
            val l: Link = if (mode == LinkMode.BLUETOOTH) {
                val d = selected
                if (d == null) {
                    error = "ابتدا HC-05 را در تنظیمات بلوتوث گوشی جفت کنید و انتخاب کنید"
                    state = LinkState.IDLE
                    return@launch
                }
                BtLink(d)
            } else {
                WifiLink(getApplication(), host.trim(), port.toIntOrNull() ?: 8080)
            }
            try {
                l.open()
                link = l
                state = LinkState.CONNECTED
                withContext(Dispatchers.IO) { l.write("PING") } // درخواست اولین دیتا
                l.readLines { parse(it) }
                if (!stopping) error = "ارتباط قطع شد"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopping) error = "اتصال ناموفق: ${e.javaClass.simpleName} ${e.message ?: ""}"
            } finally {
                l.close()
                link = null
                state = LinkState.IDLE
            }
        }
    }

    fun disconnect() {
        stopping = true
        link?.close()
        job?.cancel()
    }

    fun setRelay(index: Int, on: Boolean) {
        data = data.copy(relays = data.relays.toMutableList().also { it[index] = on })
        send("R,${index + 1},${if (on) 1 else 0}")
    }

    fun allRelays(on: Boolean) {
        data = data.copy(relays = List(4) { on })
        send("R,ALL,${if (on) 1 else 0}")
    }

    private fun send(cmd: String) {
        val l = link ?: return
        viewModelScope.launch(Dispatchers.IO) { l.write(cmd) }
    }

    /** DATA;T=25.0;H=40.0;CO=12.3;D=35.2;R=0101 */
    private fun parse(line: String) {
        if (!line.startsWith("DATA;")) return
        val m = line.split(';').drop(1).mapNotNull {
            val p = it.split('=', limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }.toMap()
        data = Telemetry(
            temp = m["T"]?.toFloatOrNull(),
            hum = m["H"]?.toFloatOrNull(),
            co = m["CO"]?.toFloatOrNull(),
            dist = m["D"]?.toFloatOrNull(),
            relays = m["R"]?.let { r -> List(4) { r.getOrNull(it) == '1' } } ?: data.relays
        )
    }

    override fun onCleared() {
        link?.close()
        super.onCleared()
    }
}
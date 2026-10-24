package com.example.megacontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B1020)
private val BgTop = Color(0xFF18244A)
private val CardBg = Color(0xFF1A2342)
private val Cyan = Color(0xFF22D3EE)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val Muted = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) vm.loadPaired()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureBluetooth()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = CardBg)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Dashboard(vm, onRefresh = ::ensureBluetooth)
                }
            }
        }
    }

    private fun ensureBluetooth() {
        if (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) vm.loadPaired()
        else permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

@Composable
fun Dashboard(vm: MainViewModel, onRefresh: () -> Unit) {
    val d = vm.data
    val connected = vm.state == LinkState.CONNECTED
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, Bg)))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(vm.state)
            ConnectionCard(vm, onRefresh)
            vm.error?.let { Text(it, color = Red, fontSize = 13.sp) }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Metric(Icons.Filled.Thermostat, "دما", d.temp.fmt(1), "°C", Amber, Modifier.weight(1f))
                Metric(Icons.Filled.WaterDrop, "رطوبت", d.hum.fmt(0), "%", Cyan, Modifier.weight(1f))
            }
            CoCard(d.co)
            DistanceCard(d.dist)
            RelayCard(vm, connected)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun Float?.fmt(dec: Int) = this?.let { "%.${dec}f".format(it) } ?: "--"

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Header(state: LinkState) {
    val (txt, col) = when (state) {
        LinkState.CONNECTED -> "متصل" to Green
        LinkState.CONNECTING -> "در حال اتصال…" to Amber
        LinkState.IDLE -> "قطع" to Red
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("کنترل‌پنل آردوینو", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Arduino Mega 2560", color = Muted, fontSize = 13.sp)
        }
        Surface(shape = RoundedCornerShape(50), color = col.copy(alpha = .15f)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(col, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(txt, color = col, fontSize = 13.sp)
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun nameOf(d: BluetoothDevice) = d.name ?: d.address

@Composable
fun ConnectionCard(vm: MainViewModel, onRefresh: () -> Unit) {
    val idle = vm.state == LinkState.IDLE
    Panel {
        Text("نوع ارتباط", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.mode == LinkMode.BLUETOOTH,
                onClick = { if (idle) vm.mode = LinkMode.BLUETOOTH },
                label = { Text("بلوتوث") },
                leadingIcon = { Icon(Icons.Filled.Bluetooth, null) }
            )
            FilterChip(
                selected = vm.mode == LinkMode.WIFI,
                onClick = { if (idle) vm.mode = LinkMode.WIFI },
                label = { Text("WiFi") },
                leadingIcon = { Icon(Icons.Filled.Wifi, null) }
            )
        }
        Spacer(Modifier.height(12.dp))

        if (vm.mode == LinkMode.BLUETOOTH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("دستگاه‌های جفت‌شده", Modifier.weight(1f), color = Color.White)
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, null, tint = Cyan) }
            }
            if (vm.devices.isEmpty()) {
                Text(
                    "ابتدا HC-05 را در تنظیمات بلوتوث گوشی جفت کنید (رمز: 1234 یا 0000)",
                    color = Muted, fontSize = 12.sp
                )
            }
            vm.devices.forEach { dev ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = idle) { vm.selected = dev },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = vm.selected?.address == dev.address, onClick = { if (idle) vm.selected = dev })
                    Column {
                        Text(nameOf(dev), color = Color.White)
                        Text(dev.address, color = Muted, fontSize = 11.sp)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = vm.host, onValueChange = { vm.host = it }, enabled = idle, singleLine = true,
                label = { Text("آدرس IP ماژول ESP8266") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.port, onValueChange = { vm.port = it.filter(Char::isDigit) }, enabled = idle,
                singleLine = true, label = { Text("پورت") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                "گوشی را به WiFi با نام MegaControl (رمز 12345678) وصل کنید",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { if (idle) vm.connect() else vm.disconnect() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (idle) Cyan else Red, contentColor = Color.Black)
        ) { Text(if (idle) "اتصال" else "قطع اتصال", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun Metric(icon: ImageVector, title: String, value: String, unit: String, tint: Color, modifier: Modifier) {
    Panel(modifier) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.height(6.dp))
        Text(title, color = Muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
fun CoCard(ppm: Float?) {
    val v = (ppm ?: 0f).coerceIn(0f, 300f)
    val sweep by animateFloatAsState(v / 300f * 240f, label = "co")
    val color = when { v < 35 -> Green; v < 100 -> Amber; else -> Red }
    val status = when { ppm == null -> "بدون داده"; v < 35 -> "ایمن"; v < 100 -> "هشدار"; else -> "خطرناک!" }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Air, null, tint = color)
            Spacer(Modifier.width(8.dp))
            Text("مونوکسید کربن (MQ-7)", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(status, color = color, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(18.dp.toPx(), cap = StrokeCap.Round)
                    val inset = 12.dp.toPx()
                    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawArc(Color(0x22FFFFFF), 150f, 240f, false, Offset(inset, inset), sz, style = stroke)
                    drawArc(color, 150f, sweep, false, Offset(inset, inset), sz, style = stroke)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ppm.fmt(0), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("ppm (تقریبی)", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun DistanceCard(cm: Float?) {
    val progress by animateFloatAsState(
        if (cm == null) 0f else (1f - cm.coerceIn(0f, 200f) / 200f), label = "dist"
    )
    val color = when { cm == null -> Muted; cm < 20 -> Red; cm < 60 -> Amber; else -> Green }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Straighten, null, tint = color)
            Spacer(Modifier.width(8.dp))
            Text("فاصله (اولتراسونیک)", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(cm.fmt(1), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(" cm", color = Muted)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = color, trackColor = Color(0x22FFFFFF), strokeCap = StrokeCap.Round
        )
    }
}

@Composable
fun RelayCard(vm: MainViewModel, enabled: Boolean) {
    val names = listOf("لامپ ۲۲۰ ولت", "کانال ۲", "کانال ۳", "کانال ۴")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("رله‌ها", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.allRelays(false) }, enabled = enabled) { Text("خاموش همه") }
        }
        for (i in 0..3) {
            val on = vm.data.relays[i]
            val tint by animateColorAsState(if (on) Amber else Muted, label = "relay$i")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (i == 0) Icons.Filled.Lightbulb else Icons.Filled.Power, null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(names[i], color = Color.White)
                    Text(if (on) "روشن" else "خاموش", color = tint, fontSize = 12.sp)
                }
                Switch(checked = on, onCheckedChange = { vm.setRelay(i, it) }, enabled = enabled)
            }
        }
    }
}

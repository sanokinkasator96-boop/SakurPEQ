package org.michaelbel.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

val NightSkyDark = Color(0xFF100E1B)
val IndigoCard = Color(0xFF1B172C)
val SakuraPink = Color(0xFFFFB7C5)
val SakuraDeep = Color(0xFFFF4D79)
val GoldAccent = Color(0xFFE6C27A)

@RequiresApi(Build.VERSION_CODES.P)
class MainActivity : ComponentActivity() {

    private var eqService: EqualizerService? = null
    private var isBound by mutableStateOf(false)
    private var isEqActive by mutableStateOf(false)
    private lateinit var storage: PresetStorage

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EqualizerService.LocalBinder
            eqService = binder.getService()
            isBound = true
            isEqActive = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            isBound = false
            isEqActive = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startAndBindService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableMaxRefreshRate()
        storage = PresetStorage(this)

        setContent {
            SakuraFiioScreen(
                storage = storage,
                isEqActive = isEqActive,
                onToggleEq = { enable ->
                    if (enable) checkPermissionsAndStart() else stopEqService()
                },
                onBandUpdated = { index, freq, gain, q ->
                    eqService?.updateBand(index, freq, gain, q)
                },
                onAllBandsUpdated = { freqs, gains, qs, profileName ->
                    eqService?.updateAllBands(freqs, gains, qs)
                    eqService?.updateNotificationSubtitle("Профиль: $profileName")
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        isEqActive = EqualizerService.isRunning
        if (EqualizerService.isRunning && !isBound) {
            bindService(Intent(this, EqualizerService::class.java), connection, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun enableMaxRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val maxMode = display?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = maxMode.modeId
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun checkPermissionsAndStart() {
        requestBatteryExemption()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, EqualizerService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        isEqActive = true
    }

    private fun stopEqService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, EqualizerService::class.java))
        isEqActive = false
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SakuraFiioScreen(
    storage: PresetStorage,
    isEqActive: Boolean,
    onToggleEq: (Boolean) -> Unit,
    onBandUpdated: (Int, Float, Float, Float) -> Unit,
    onAllBandsUpdated: (List<Float>, List<Float>, List<Float>, String) -> Unit
) {
    val freqs = remember { mutableStateListOf<Float>().apply { addAll(storage.loadLastFreqs()) } }
    val gains = remember { mutableStateListOf<Float>().apply { addAll(storage.loadLastGains()) } }
    val qs = remember { mutableStateListOf<Float>().apply { addAll(storage.loadLastQs()) } }

    var selectedBand by remember { mutableIntStateOf(0) }
    var userPresets by remember { mutableStateOf(storage.getPresetNames()) }
    var activeProfile by remember { mutableStateOf("Ручной") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NightSkyDark, Color(0xFF1D152E))))
    ) {
        SakuraPetalsBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "桜 音響 • SAKURA PEQ",
                        color = SakuraPink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = if (isEqActive) "Активен • $activeProfile" else "Отключен",
                        color = if (isEqActive) GoldAccent else Color.Gray,
                        fontSize = 13.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isEqActive) SakuraDeep else IndigoCard)
                        .border(1.5.dp, SakuraPink, RoundedCornerShape(12.dp))
                        .clickable { onToggleEq(!isEqActive) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isEqActive) "入" else "切",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(SakuraDeep.copy(alpha = 0.25f))
                        .border(1.dp, SakuraDeep, RoundedCornerShape(18.dp))
                        .clickable { showSaveDialog = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("+ Сохранить", color = SakuraPink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(IndigoCard)
                        .border(1.dp, SakuraPink.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .clickable {
                            for (i in 0 until 10) {
                                freqs[i] = storage.defaultFreqs[i]
                                gains[i] = 0f
                                qs[i] = 1.4f
                            }
                            activeProfile = "Сброс (0 дБ)"
                            storage.saveLastState(freqs, gains, qs)
                            onAllBandsUpdated(freqs, gains, qs, activeProfile)
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("Сброс", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }

                userPresets.forEach { name ->
                    val isSelected = activeProfile == name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isSelected) SakuraPink.copy(alpha = 0.22f) else IndigoCard)
                            .border(
                                1.dp,
                                if (isSelected) GoldAccent else SakuraPink.copy(alpha = 0.35f),
                                RoundedCornerShape(18.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    storage.getUserPreset(name)?.let { (f, g, q) ->
                                        for (i in 0 until 10) {
                                            freqs[i] = f[i]
                                            gains[i] = g[i]
                                            qs[i] = q[i]
                                        }
                                        activeProfile = name
                                        storage.saveLastState(freqs, gains, qs)
                                        onAllBandsUpdated(freqs, gains, qs, name)
                                    }
                                },
                                onLongClick = {
                                    storage.deleteUserPreset(name)
                                    userPresets = storage.getPresetNames()
                                    if (activeProfile == name) activeProfile = "Ручной"
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(name, color = if (isSelected) GoldAccent else SakuraPink, fontSize = 12.sp)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(IndigoCard.copy(alpha = 0.85f))
                    .border(1.dp, SakuraPink.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    .padding(12.dp)
            ) {
                FiioFrequencyGraph(
                    freqs = freqs,
                    gains = gains,
                    qValues = qs,
                    selectedBand = selectedBand,
                    onSelectBand = { selectedBand = it },
                    onPointDragged = { idx, newFreq, newGain ->
                        freqs[idx] = newFreq.roundToInt().toFloat()
                        gains[idx] = (newGain * 10f).roundToInt() / 10f
                        activeProfile = "Ручной"
                        onBandUpdated(idx, freqs[idx], gains[idx], qs[idx])
                        storage.saveLastState(freqs, gains, qs)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0 until 10) {
                    val isSel = i == selectedBand
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(if (isSel) SakuraDeep else IndigoCard)
                            .border(1.dp, if (isSel) SakuraPink else SakuraPink.copy(alpha = 0.3f), CircleShape)
                            .clickable { selectedBand = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${i + 1}",
                            color = if (isSel) Color.White else SakuraPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(IndigoCard.copy(alpha = 0.85f))
                    .border(1.dp, SakuraPink.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Полоса #${selectedBand + 1}",
                    color = GoldAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                val minLog = ln(20f)
                val maxLog = ln(20000f)
                val freqSliderPos = ((ln(freqs[selectedBand]) - minLog) / (maxLog - minLog)).coerceIn(0f, 1f)

                ParameterControlRow(
                    label = "Частота (Freq)",
                    valueText = "${freqs[selectedBand].roundToInt()} Гц",
                    sliderValue = freqSliderPos,
                    valueRange = 0f..1f,
                    onMinus = {
                        val next = (freqs[selectedBand] - 1f).coerceIn(20f, 20000f)
                        freqs[selectedBand] = next
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, next, gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    },
                    onPlus = {
                        val next = (freqs[selectedBand] + 1f).coerceIn(20f, 20000f)
                        freqs[selectedBand] = next
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, next, gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    },
                    onValueChange = { ratio ->
                        val newFreq = exp(minLog + ratio * (maxLog - minLog)).roundToInt().toFloat()
                        freqs[selectedBand] = newFreq
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, newFreq, gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    }
                )

                ParameterControlRow(
                    label = "Усиление (Gain)",
                    valueText = String.format(Locale.US, "%+.1f дБ", gains[selectedBand]),
                    sliderValue = gains[selectedBand],
                    valueRange = -12f..12f,
                    onMinus = {
                        val next = ((gains[selectedBand] - 0.5f) * 10f).roundToInt() / 10f
                        gains[selectedBand] = next.coerceIn(-12f, 12f)
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, freqs[selectedBand], gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    },
                    onPlus = {
                        val next = ((gains[selectedBand] + 0.5f) * 10f).roundToInt() / 10f
                        gains[selectedBand] = next.coerceIn(-12f, 12f)
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, freqs[selectedBand], gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    },
                    onValueChange = { newGain ->
                        val rounded = (newGain * 10f).roundToInt() / 10f
                        gains[selectedBand] = rounded
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, freqs[selectedBand], rounded, qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    }
                )

                ParameterControlRow(
                    label = "Добротность (Q)",
                    valueText = String.format(Locale.US, "%.2f", qs[selectedBand]),
                    sliderValue = qs[selectedBand],
                    valueRange = 0.4f..8.0f,
                    onMinus = {
                        val next = ((qs[selectedBand] - 0.1f) * 100f).roundToInt() / 100f
                        qs[selectedBand] = next.coerceIn(0.4f, 8.0f)
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, freqs[selectedBand], gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    },
                    onPlus = {
                        val next = ((qs[selectedBand] + 0.1f) * 100f).roundToInt() / 100f
                        qs[selectedBand] = next.coerceIn(0.4f, 8.0f)
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, freqs[selectedBand], gains[selectedBand], qs[selectedBand])
                        storage.saveLastState(freqs, gains, qs)
                    },
                    onValueChange = { newQ ->
                        val rounded = (newQ * 100f).roundToInt() / 100f
                        qs[selectedBand] = rounded
                        activeProfile = "Ручной"
                        onBandUpdated(selectedBand, freqs[selectedBand], gains[selectedBand], rounded)
                        storage.saveLastState(freqs, gains, qs)
                    }
                )
            }
        }

        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                containerColor = IndigoCard,
                title = { Text("Новый профиль", color = SakuraPink) },
                text = {
                    OutlinedTextField(
                        value = presetInput,
                        onValueChange = { presetInput = it },
                        placeholder = { Text("Название профиля", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SakuraPink
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clean = presetInput.trim()
                        if (clean.isNotEmpty()) {
                            storage.saveUserPreset(clean, freqs, gains, qs)
                            userPresets = storage.getPresetNames()
                            activeProfile = clean
                            onAllBandsUpdated(freqs, gains, qs, clean)
                            presetInput = ""
                            showSaveDialog = false
                        }
                    }) { Text("Сохранить", color = GoldAccent, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text("Отмена", color = Color.Gray)
                    }
                }
            )
        }
    }
}
@Composable
fun ParameterControlRow(
    label: String,
    valueText: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(NightSkyDark)
                        .clickable { onMinus() },
                    contentAlignment = Alignment.Center
                ) { Text("-", color = SakuraPink, fontWeight = FontWeight.Bold) }

                Text(text = valueText, color = SakuraPink, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(NightSkyDark)
                        .clickable { onPlus() },
                    contentAlignment = Alignment.Center
                ) { Text("+", color = SakuraPink, fontWeight = FontWeight.Bold) }
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = SakuraPink,
                activeTrackColor = SakuraDeep,
                inactiveTrackColor = NightSkyDark
            )
        )
    }
}

@Composable
fun FiioFrequencyGraph(
    freqs: List<Float>,
    gains: List<Float>,
    qValues: List<Float>,
    selectedBand: Int,
    onSelectBand: (Int) -> Unit,
    onPointDragged: (Int, Float, Float) -> Unit
) {
    val minLog = ln(20f)
    val maxLog = ln(20000f)
    val currentSelected by rememberUpdatedState(selectedBand)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        var closest = currentSelected
                        var minDist = Float.MAX_VALUE
                        for (i in 0 until 10) {
                            val px = ((ln(freqs[i]) - minLog) / (maxLog - minLog)) * w
                            val py = h / 2f - (gains[i] / 12f) * (h / 2f)
                            val dist = (offset - Offset(px, py)).getDistance()
                            if (dist < minDist) {
                                minDist = dist
                                closest = i
                            }
                        }
                        onSelectBand(closest)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val xRatio = (change.position.x / w).coerceIn(0f, 1f)
                        val yRatio = ((h / 2f - change.position.y) / (h / 2f)).coerceIn(-1f, 1f)
                        val newFreq = exp(minLog + xRatio * (maxLog - minLog))
                        val newGain = yRatio * 12f
                        onPointDragged(currentSelected, newFreq, newGain)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f

        listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f).forEach { gf ->
            val x = ((ln(gf) - minLog) / (maxLog - minLog)) * w
            drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        }

        listOf(-6f, 0f, 6f).forEach { db ->
            val y = centerY - (db / 12f) * centerY
            drawLine(
                color = if (db == 0f) SakuraPink.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx()
            )
        }

        val curvePath = Path()
        val fillPath = Path()
        val steps = 120

        for (step in 0..steps) {
            val ratio = step.toFloat() / steps
            val currentLogFreq = minLog + ratio * (maxLog - minLog)
            var sumGain = 0f

            for (j in 0 until 10) {
                val dist = abs(currentLogFreq - ln(freqs[j]))
                val width = 1.0f / qValues[j]
                sumGain += gains[j] * exp(-(dist * dist) / (2f * width * width))
            }

            val x = ratio * w
            val y = (centerY - (sumGain.coerceIn(-15f, 15f) / 12f) * centerY).coerceIn(0f, h)

            if (step == 0) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, centerY)
                fillPath.lineTo(x, y)
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (step == steps) {
                fillPath.lineTo(x, centerY)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(listOf(SakuraDeep.copy(alpha = 0.35f), Color.Transparent))
        )
        drawPath(path = curvePath, color = SakuraPink, style = Stroke(width = 2.5.dp.toPx()))

        for (i in 0 until 10) {
            val px = ((ln(freqs[i]) - minLog) / (maxLog - minLog)) * w
            val py = (centerY - (gains[i] / 12f) * centerY).coerceIn(0f, h)
            val isSel = i == selectedBand

            if (isSel) {
                drawCircle(SakuraDeep.copy(alpha = 0.35f), 14.dp.toPx(), Offset(px, py))
            }
            drawCircle(
                color = if (isSel) SakuraDeep else SakuraPink,
                radius = if (isSel) 7.dp.toPx() else 4.5.dp.toPx(),
                center = Offset(px, py)
            )
        }
    }
}

@Composable
fun SakuraPetalsBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "sakura")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
        label = "fall"
    )

    val petals = remember {
        List(16) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 7f + 5f) }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        petals.forEachIndexed { index, (startX, startY, size) ->
            val progress = (startY + time) % 1f
            val y = progress * this.size.height
            val sway = sin(progress * 6.28f + index) * 30f
            val x = (startX * this.size.width + sway).coerceIn(0f, this.size.width)

            val path = Path().apply {
                moveTo(x, y)
                quadraticTo(x + size, y - size, x + size * 2, y)
                quadraticTo(x + size, y + size * 1.5f, x, y)
                close()
            }
            drawPath(path = path, color = SakuraPink.copy(alpha = 0.2f))
        }
    }
}

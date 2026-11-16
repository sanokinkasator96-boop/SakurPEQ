package org.michaelbel.myapplication

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.DynamicsProcessing
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

@RequiresApi(Build.VERSION_CODES.P)
class TenBandEqualizer(audioSessionId: Int = 0) {

    private val freqs = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val gains = FloatArray(10) { 0f }
    private val qValues = FloatArray(10) { 1.4f }
    private var dynamicsProcessing: DynamicsProcessing? = null
    var errorMessage: String? = null

    init {
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1, true, 10,
                false, 0, false, 0,
                true
            ).build()

            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply {
                enabled = true
            }
            recalculateAllBands()
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Ошибка аудиодвижка"
        }
    }

    fun setAllBands(newFreqs: List<Float>, newGains: List<Float>, newQs: List<Float>) {
        if (newFreqs.size == 10 && newGains.size == 10 && newQs.size == 10) {
            for (i in 0 until 10) {
                freqs[i] = newFreqs[i].coerceIn(20f, 20000f)
                gains[i] = newGains[i].coerceIn(-12f, 12f)
                qValues[i] = newQs[i].coerceIn(0.4f, 8.0f)
            }
            recalculateAllBands()
        }
    }

    fun setBandParams(index: Int, freq: Float, gain: Float, q: Float) {
        if (index in 0 until 10) {
            freqs[index] = freq.coerceIn(20f, 20000f)
            gains[index] = gain.coerceIn(-12f, 12f)
            qValues[index] = q.coerceIn(0.4f, 8.0f)
            recalculateAllBands()
        }
    }

    private fun recalculateAllBands() {
        val dp = dynamicsProcessing ?: return
        try {
            for (i in 0 until 10) {
                var totalGain = 0f
                val targetLogFreq = ln(freqs[i])

                for (j in 0 until 10) {
                    val sourceLogFreq = ln(freqs[j])
                    val distance = abs(targetLogFreq - sourceLogFreq)
                    val width = 1.0f / qValues[j]
                    val influence = exp(-(distance * distance) / (2f * width * width))
                    totalGain += gains[j] * influence
                }

                val band = DynamicsProcessing.EqBand(
                    true,
                    freqs[i],
                    totalGain.coerceIn(-15f, 15f)
                )
                dp.setPreEqBandAllChannelsTo(i, band)
            }
        } catch (_: Exception) { }
    }

    fun release() {
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (_: Exception) {
        } finally {
            dynamicsProcessing = null
        }
    }
}

class PresetStorage(context: Context) {
    private val prefs = context.getSharedPreferences("sakura_fiio_peq", Context.MODE_PRIVATE)
    val defaultFreqs = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val defaultGains = List(10) { 0f }
    val defaultQs = List(10) { 1.4f }

    fun saveLastState(freqs: List<Float>, gains: List<Float>, qs: List<Float>) {
        prefs.edit()
            .putString("last_freqs", freqs.joinToString(","))
            .putString("last_gains", gains.joinToString(","))
            .putString("last_qs", qs.joinToString(","))
            .apply()
    }

    fun loadLastFreqs(): List<Float> = parseList("last_freqs", defaultFreqs)
    fun loadLastGains(): List<Float> = parseList("last_gains", defaultGains)
    fun loadLastQs(): List<Float> = parseList("last_qs", defaultQs)

    fun saveUserPreset(name: String, freqs: List<Float>, gains: List<Float>, qs: List<Float>) {
        val names = getPresetNames().toMutableSet()
        names.add(name)
        prefs.edit()
            .putStringSet("preset_names", names)
            .putString("p_freqs_$name", freqs.joinToString(","))
            .putString("p_gains_$name", gains.joinToString(","))
            .putString("p_qs_$name", qs.joinToString(","))
            .apply()
    }

    fun getUserPreset(name: String): Triple<List<Float>, List<Float>, List<Float>>? {
        val f = parseList("p_freqs_$name", emptyList())
        val g = parseList("p_gains_$name", emptyList())
        val q = parseList("p_qs_$name", emptyList())
        if (f.size != 10 || g.size != 10 || q.size != 10) return null
        return Triple(f, g, q)
    }

    fun getPresetNames(): List<String> =
        prefs.getStringSet("preset_names", emptySet())?.toList()?.sorted() ?: emptyList()

    fun deleteUserPreset(name: String) {
        val names = getPresetNames().toMutableSet()
        names.remove(name)
        prefs.edit()
            .putStringSet("preset_names", names)
            .remove("p_freqs_$name")
            .remove("p_gains_$name")
            .remove("p_qs_$name")
            .apply()
    }

    private fun parseList(key: String, fallback: List<Float>): List<Float> {
        val str = prefs.getString(key, null) ?: return fallback
        val parsed = str.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parsed.size == 10) parsed else fallback
    }
}
@RequiresApi(Build.VERSION_CODES.P)
class EqualizerService : Service() {

    private var equalizer: TenBandEqualizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    companion object {
        const val CHANNEL_ID = "SakuraPeqChannel"
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP = "STOP_SAKURA_PEQ"
        var isRunning = false
    }

    inner class LocalBinder : Binder() {
        fun getService(): EqualizerService = this@EqualizerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SakuraPEQ::AudioWakeLock"
        ).apply { acquire() }

        equalizer = TenBandEqualizer(audioSessionId = 0).apply {
            val storage = PresetStorage(this@EqualizerService)
            setAllBands(storage.loadLastFreqs(), storage.loadLastGains(), storage.loadLastQs())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Активен • 10-полосный PEQ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    fun updateBand(index: Int, freq: Float, gain: Float, q: Float) {
        equalizer?.setBandParams(index, freq, gain, q)
    }

    fun updateAllBands(freqs: List<Float>, gains: List<Float>, qs: List<Float>) {
        equalizer?.setAllBands(freqs, gains, qs)
    }

    fun updateNotificationSubtitle(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EqualizerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌸 桜 音響 • SAKURA PEQ")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setColor(0xFFFF4D79.toInt())
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "切 ВЫКЛ", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sakura PEQ Фоновый звук",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, EqualizerService::class.java).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getService(
            this, 2, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        equalizer?.release()
        equalizer = null
        super.onDestroy()
    }
}

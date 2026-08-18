package com.voicenote.app.core.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.voicenote.app.MainActivity
import com.voicenote.app.R
import com.voicenote.app.core.audio.AudioCapture
import com.voicenote.app.core.audio.AudioFileManager
import com.voicenote.app.data.repository.VoiceRecordRepositoryImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import javax.inject.Inject

/**
 * 纯录音服务 — 只负责录音并保存 WAV 文件，不做任何本地转写。
 * 转写由服务端在上传后自动完成。
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var audioCapture: AudioCapture
    @Inject lateinit var recordRepository: VoiceRecordRepositoryImpl
    @Inject lateinit var audioFileManager: AudioFileManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled coroutine exception: ${e.message}", e)
        })
    private var recordingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var actualStopTime: java.time.Instant? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var wakeLockStartTime = 0L
    private var diskCheckJob: Job? = null
    private var thermalWarningShown = false

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.voicenote.app.action.START_RECORDING"
        const val ACTION_STOP = "com.voicenote.app.action.STOP_RECORDING"
        const val EXTRA_RECORD_ID = "record_id"

        private const val DISK_CHECK_INTERVAL_MS = 300_000L   // 5 minutes
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024  // 500 MB
        private const val BATTERY_WARNING_SECONDS = 3600L    // 1 hour

        // Observables for UI binding
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _durationSeconds = MutableStateFlow(0L)
        val durationSeconds: StateFlow<Long> = _durationSeconds.asStateFlow()

        private val _statusMessage = MutableStateFlow("")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

        private var durationJob: Job? = null
        private var currentRecordId: Long = 0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (_isRecording.value) {
                    Log.w(TAG, "Ignoring duplicate START request — already recording")
                    return START_REDELIVER_INTENT
                }
                val recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0)
                startRecording(recordId)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(recordId: Long) {
        try {
            currentRecordId = recordId
            _isRecording.value = true
            _durationSeconds.value = 0
            _statusMessage.value = "正在初始化录音..."
            thermalWarningShown = false

            // Acquire wake lock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VoiceNote:RecordingWakeLock"
            ).apply { acquire() }
            wakeLockStartTime = System.currentTimeMillis()

            // Request audio focus
            requestAudioFocus()

            // Start foreground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification("录音中..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("录音中..."))
            }

            // Initialize audio file
            audioFileManager.startNewRecording(recordId, java.time.Instant.now())

            // Launch recording loop
            startRecordingLoop()

            // Launch post-recording finalization
            startFinalization()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            _isRecording.value = false
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun startRecordingLoop() {
        recordingJob = serviceScope.launch {
            // Disk space monitor
            diskCheckJob = launch {
                while (isActive) {
                    delay(DISK_CHECK_INTERVAL_MS)
                    if (!checkDiskSpace()) {
                        Log.w(TAG, "磁盘空间不足，自动停止录音")
                        stopRecording()
                        break
                    }
                }
            }

            // Thermal monitor
            launch {
                while (isActive) {
                    delay(120_000L) // 2 minutes
                    checkThermalStatus()
                }
            }

            try {
                startDurationCounter()
                audioCapture.startCapture().collect { audioData ->
                    audioFileManager.writeAudioChunk(audioData)
                    _audioLevel.value = computeAudioLevel(audioData)
                    _statusMessage.value = "录音中... ${formatDuration(_durationSeconds.value)}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
            }
        }
    }

    private fun computeAudioLevel(pcmData: ByteArray): Float {
        if (pcmData.size < 2) return 0f
        val sampleCount = pcmData.size / 2
        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSquares / sampleCount)
        return minOf(1f, (rms * 12.0).toFloat())
    }

    private fun startFinalization() {
        serviceScope.launch {
            recordingJob?.join()

            val audioFilePath = audioFileManager.finalizeRecording()
            recordRepository.updateAudioFilePath(
                currentRecordId, audioFilePath,
                actualStopTime ?: java.time.Instant.now()
            )

            _isRecording.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun stopRecording() {
        actualStopTime = java.time.Instant.now()
        durationJob?.cancel()
        diskCheckJob?.cancel()
        audioCapture.stopCapture()

        _isRecording.value = false
        _statusMessage.value = "录音已结束，正在保存..."
        updateNotification("录音已结束，正在保存...")
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(focusAttributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                // Continue recording
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {}
                        }
                    }
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        }
        audioFocusRequest = null
    }

    private suspend fun startDurationCounter() {
        recordRepository.updateStartTime(currentRecordId, java.time.Instant.now())
        _durationSeconds.value = 0
        var batteryWarned = false
        durationJob = serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                _durationSeconds.value += 1
                if (!batteryWarned && _durationSeconds.value >= BATTERY_WARNING_SECONDS) {
                    batteryWarned = true
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        updateNotification("⚡ 建议关闭电池优化以确保持续录音")
                    }
                }
            }
        }
    }

    private fun checkDiskSpace(): Boolean {
        val usableSpace = filesDir.usableSpace
        if (usableSpace < MIN_FREE_SPACE_BYTES) {
            _statusMessage.value = "磁盘空间不足，请停止录音"
            updateNotification("磁盘空间不足 (剩余 ${usableSpace / 1_048_576}MB)")
            return false
        }
        if (audioFileManager.hasWriteError()) {
            _statusMessage.value = "磁盘写入失败，请停止录音"
            updateNotification("磁盘写入失败，录音已中断")
            return false
        }
        return true
    }

    @SuppressLint("NewApi")
    private fun checkThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (thermalWarningShown) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            thermalWarningShown = true
            Log.w(TAG, "设备过热，建议结束录音")
            _statusMessage.value = "⚠️ 设备过热，建议结束录音"
            updateNotification("⚠️ 设备过热，建议结束录音")
        }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音笔记")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification_stop, "结束",
                PendingIntent.getService(this, 1,
                    Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "录音进行中" }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        abandonAudioFocus()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
package com.voicenote.app.ui.detail

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicenote.app.core.audio.AudioFileManager
import com.voicenote.app.core.di.SettingsDataStore
import com.voicenote.app.core.network.ServerClient
import com.voicenote.app.core.network.UploadMetadata
import com.voicenote.app.domain.model.VoiceRecord
import com.voicenote.app.domain.repository.VoiceRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject

enum class PlaybackState { IDLE, PLAYING, PAUSED }

data class DetailUiState(
    val record: VoiceRecord? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val playbackProgress: Float = 0f,
    val playbackPositionFormatted: String = "00:00",
    val playbackDurationFormatted: String = "00:00",
    val showDeleteConfirm: Boolean = false,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    // 上传
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadMessage: String = "",
    val uploadedRecordId: String = "",
    // 转写状态
    val transcriptStatus: String = "",
    val transcriptText: String = "",
    // 上传前填写信息
    val inspectorName: String = "",
    val customerName: String = "",
    val customerAddress: String = "",
    val showUploadDialog: Boolean = false
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    application: Application,
    private val recordRepository: VoiceRecordRepository,
    private val audioFileManager: AudioFileManager,
    private val settingsDataStore: SettingsDataStore
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    // AudioTrack playback state
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var positionUpdateJob: Job? = null

    // Parsed WAV header fields
    private var wavDataOffset: Long = 44
    private var wavDataSize: Long = 0
    private var wavSampleRate: Int = 16000
    private var wavChannels: Int = 1
    private var totalFrames: Long = 0
    @Volatile private var playbackBaseFrame: Long = 0

    fun loadRecord(recordId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val record = recordRepository.getRecordById(recordId)
                _uiState.value = _uiState.value.copy(
                    record = record,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    // ── 上传信息 ──

    fun updateInspectorName(name: String) {
        _uiState.value = _uiState.value.copy(inspectorName = name)
    }

    fun updateCustomerName(name: String) {
        _uiState.value = _uiState.value.copy(customerName = name)
    }

    fun updateCustomerAddress(address: String) {
        _uiState.value = _uiState.value.copy(customerAddress = address)
    }

    fun showUploadDialog() {
        _uiState.value = _uiState.value.copy(showUploadDialog = true)
    }

    fun dismissUploadDialog() {
        _uiState.value = _uiState.value.copy(showUploadDialog = false)
    }

    fun uploadToServer() {
        val state = _uiState.value
        val record = state.record ?: return
        val audioFile = File(record.audioFilePath)
        if (!audioFile.exists()) {
            _uiState.value = state.copy(uploadMessage = "音频文件不存在")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, uploadProgress = 0f, uploadMessage = "正在上传...")

            try {
                val settings = settingsDataStore.settingsFlow.first()
                val client = ServerClient(settings.serverUrl, settings.serverApiKey)

                val metadata = UploadMetadata(
                    id = record.id.toString(),
                    title = record.title ?: "",
                    description = record.memo ?: "",
                    inspector_name = state.inspectorName,
                    customer_name = state.customerName,
                    customer_address = state.customerAddress,
                    inspection_date = record.startTime?.toString() ?: "",
                    source_type = "RECORDING"
                )

                val result = client.upload(audioFile, metadata)
                result.onSuccess { uploadResult ->
                    if (uploadResult.success) {
                        _uiState.value = _uiState.value.copy(
                            isUploading = false,
                            uploadProgress = 1f,
                            uploadMessage = "上传成功！服务端自动转写中...",
                            uploadedRecordId = uploadResult.recordId,
                            transcriptStatus = uploadResult.transcriptStatus
                        )
                        pollTranscriptStatus(uploadResult.recordId, client)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isUploading = false,
                            uploadMessage = "上传失败: ${uploadResult.error}"
                        )
                    }
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadMessage = "上传失败: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadMessage = "网络错误: ${e.message}"
                )
            }
        }
    }

    private fun pollTranscriptStatus(recordId: String, client: ServerClient) {
        viewModelScope.launch {
            repeat(60) { // 最多轮询 60 次（2 分钟）
                delay(2000)
                val result = client.getTranscriptStatus(recordId)
                result.onSuccess { status ->
                    _uiState.value = _uiState.value.copy(
                        transcriptStatus = status.transcriptStatus,
                        transcriptText = status.transcriptText,
                        uploadMessage = status.message
                    )
                    if (status.transcriptStatus == "COMPLETED" || status.transcriptStatus == "FAILED") {
                        return@repeat
                    }
                }
            }
        }
    }

    // ── 音频播放 ──

    fun play() {
        val record = _uiState.value.record ?: return
        val file = File(record.audioFilePath)
        if (!file.exists()) {
            _uiState.value = _uiState.value.copy(error = "音频文件不存在")
            return
        }

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                if (state.playbackState == PlaybackState.PLAYING) {
                    // Pause
                    audioTrack?.pause()
                    positionUpdateJob?.cancel()
                    _uiState.value = state.copy(playbackState = PlaybackState.PAUSED)
                    return@launch
                }

                if (state.playbackState == PlaybackState.PAUSED) {
                    // Resume
                    audioTrack?.play()
                    startPositionUpdate()
                    _uiState.value = _uiState.value.copy(playbackState = PlaybackState.PLAYING)
                    return@launch
                }

                // Start playback
                parseWavHeader(file)
                audioTrack?.release()
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(wavSampleRate)
                            .setChannelMask(
                                if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO
                                else AudioFormat.CHANNEL_OUT_STEREO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(
                        wavSampleRate,
                        if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ))
                    .build()

                audioTrack?.play()
                _uiState.value = _uiState.value.copy(playbackState = PlaybackState.PLAYING)
                startPositionUpdate()

                streamAudio(file)
            } catch (e: Exception) {
                Log.e(TAG, "Playback error: ${e.message}", e)
                _uiState.value = _uiState.value.copy(playbackState = PlaybackState.IDLE, error = e.message)
            }
        }
    }

    private fun parseWavHeader(file: File) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(22)
                wavChannels = raf.readShort().toInt() and 0xFFFF
                wavSampleRate = raf.readInt()
                raf.seek(34)
                val bits = raf.readShort().toInt() and 0xFFFF
                wavDataOffset = 44
                wavDataSize = file.length() - wavDataOffset
                totalFrames = wavDataSize / (wavChannels * bits / 8)
            }
        } catch (_: Exception) {}
    }

    private suspend fun streamAudio(file: File) {
        withContext(Dispatchers.IO) {
            try {
                val bufferSize = wavSampleRate * wavChannels * 2 // 1 second buffer
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(wavDataOffset + playbackBaseFrame * wavChannels.toLong() * 2)
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int
                    while (isActive && _uiState.value.playbackState == PlaybackState.PLAYING) {
                        bytesRead = raf.read(buffer)
                        if (bytesRead <= 0) break
                        audioTrack?.write(buffer, 0, bytesRead)
                        playbackBaseFrame += bytesRead / (wavChannels * 2)
                    }
                }
            } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(playbackState = PlaybackState.IDLE)
        }
    }

    private fun startPositionUpdate() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (isActive && _uiState.value.playbackState == PlaybackState.PLAYING) {
                val currentFrame = playbackBaseFrame +
                    (audioTrack?.playbackHeadPosition?.toLong() ?: 0L)
                val progress = if (totalFrames > 0) currentFrame.toFloat() / totalFrames else 0f
                val positionSec = currentFrame / wavSampleRate
                val durationSec = totalFrames / wavSampleRate
                _uiState.value = _uiState.value.copy(
                    playbackProgress = progress.coerceIn(0f, 1f),
                    playbackPositionFormatted = formatTime(positionSec),
                    playbackDurationFormatted = formatTime(durationSec)
                )
                delay(200)
            }
        }
    }

    fun skipForward() { seekBy(15) }
    fun skipBackward() { seekBy(-15) }

    private fun seekBy(seconds: Int) {
        val delta = seconds.toLong() * wavSampleRate
        playbackBaseFrame = (playbackBaseFrame + delta).coerceIn(0, totalFrames)
        if (_uiState.value.playbackState == PlaybackState.PLAYING) {
            audioTrack?.flush()
        }
    }

    fun seekTo(progress: Float) {
        playbackBaseFrame = (totalFrames * progress).toLong()
        if (_uiState.value.playbackState == PlaybackState.PLAYING) {
            audioTrack?.flush()
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        positionUpdateJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        playbackBaseFrame = 0
        _uiState.value = _uiState.value.copy(
            playbackState = PlaybackState.IDLE,
            playbackProgress = 0f,
            playbackPositionFormatted = "00:00"
        )
    }

    // ── 删除 ──

    fun showDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = true)
    }

    fun dismissDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = false)
    }

    fun deleteRecord() {
        val record = _uiState.value.record ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            try {
                stopPlayback()
                File(record.audioFilePath).delete()
                recordRepository.deleteRecord(record.id)
                _uiState.value = _uiState.value.copy(isDeleting = false, isDeleted = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isDeleting = false, error = e.message)
            }
        }
    }

    // ── 分享 ──

    fun getShareIntent(): Intent? {
        val record = _uiState.value.record ?: return null
        val file = File(record.audioFilePath)
        if (!file.exists()) return null

        val uri: Uri = FileProvider.getUriForFile(
            getApplication(), "${getApplication<Application>().packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

    companion object {
        private const val TAG = "DetailViewModel"

        fun formatTime(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            val m = s / 60
            val sec = s % 60
            return "%02d:%02d".format(m, sec)
        }
    }
}
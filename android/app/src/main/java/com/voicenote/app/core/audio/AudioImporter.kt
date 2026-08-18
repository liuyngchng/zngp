package com.voicenote.app.core.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.voicenote.app.domain.model.VoiceRecord
import com.voicenote.app.domain.repository.VoiceRecordRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频文件导入器 — 导入外部音频文件，转写由服务端完成。
 */
@Singleton
class AudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordRepository: VoiceRecordRepository,
    private val audioFileManager: AudioFileManager
) {

    suspend fun importAudio(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val timestamp = Instant.now()
            val title = "导入音频 ${dateFormatter.format(timestamp)}"

            val importedDir = File(context.filesDir, "audio/imported")
            importedDir.mkdirs()

            val safeName = "import_${safeDateFormatter.format(timestamp)}"
            val targetFile = File(importedDir, "$safeName.wav")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("无法读取选择的音频文件"))

            Log.i(TAG, "导入音频: ${targetFile.absolutePath} (${targetFile.length()} bytes)")

            val durationMs = try {
                val retriever = MediaMetadataRetriever()
                retriever.use {
                    it.setDataSource(targetFile.absolutePath)
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                }
            } catch (_: Exception) { 0L }

            val record = VoiceRecord(
                title = title,
                memo = "",
                description = "",
                speakers = emptyList(),
                sourceType = "IMPORTED",
                startTime = timestamp,
                endTime = if (durationMs > 0) timestamp.plusMillis(durationMs) else null,
                audioFilePath = targetFile.absolutePath
            )

            val recordId = recordRepository.createRecord(record)
            Log.i(TAG, "录音记录已创建: recordId=$recordId（转写将由服务端完成）")

            Result.success(recordId)
        } catch (e: Exception) {
            Log.e(TAG, "导入音频失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "AudioImporter"
        private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .withZone(ZoneId.systemDefault())
        private val safeDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
    }
}
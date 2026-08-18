package com.voicenote.app.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 上传记录元数据
 */
data class UploadMetadata(
    val id: String,
    val title: String = "",
    val description: String = "",
    val inspector_name: String = "",
    val customer_name: String = "",
    val customer_address: String = "",
    val inspection_date: String = "",
    val source_type: String = "RECORDING"
)

/**
 * 上传结果
 */
data class UploadResult(
    val success: Boolean,
    val recordId: String = "",
    val transcriptStatus: String = "",
    val message: String = "",
    val error: String = ""
)

/**
 * 转写状态
 */
data class TranscriptStatus(
    val transcriptStatus: String = "",
    val transcriptText: String = "",
    val message: String = ""
)

/**
 * 质检结果
 */
data class InspectionResult(
    val overallConclusion: String = "",
    val overallScore: Int = 0,
    val summary: String = "",
    val items: List<InspectionItemResult> = emptyList()
)

data class InspectionItemResult(
    val itemName: String = "",
    val verdict: String = "",
    val evidence: String = "",
    val confidence: Double = 0.0,
    val aiReasoning: String = ""
)

/**
 * 服务器客户端 — 上传录音、查询转写状态、查询质检结果
 */
class ServerClient(
    private val baseUrl: String = "http://localhost:8080",
    private val apiKey: String = ""
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ServerClient"
    }

    /**
     * 上传录音文件 + 元数据
     */
    suspend fun upload(audioFile: File, metadata: UploadMetadata): Result<UploadResult> =
        withContext(Dispatchers.IO) {
            try {
                val metadataJson = gson.toJson(metadata)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("metadata", metadataJson)
                    .addFormDataPart("audio", audioFile.name,
                        audioFile.asRequestBody("audio/wav".toMediaType()))
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/api/records")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val record = json.getAsJsonObject("record")
                    Result.success(UploadResult(
                        success = true,
                        recordId = record?.get("id")?.asString ?: "",
                        transcriptStatus = json.get("transcript_status")?.asString ?: "PENDING",
                        message = json.get("message")?.asString ?: "上传成功"
                    ))
                } else {
                    val error = try {
                        JsonParser.parseString(body).asJsonObject.get("error")?.asString
                    } catch (_: Exception) { "HTTP ${response.code}" }
                    Result.success(UploadResult(success = false, error = error ?: "未知错误"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传失败: ${e.message}", e)
                Result.success(UploadResult(success = false, error = e.message ?: "网络错误"))
            }
        }

    /**
     * 查询转写状态
     */
    suspend fun getTranscriptStatus(recordId: String): Result<TranscriptStatus> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/records/$recordId/transcript-status")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val status = gson.fromJson(body, TranscriptStatus::class.java)
                    Result.success(status)
                } else {
                    Result.failure(Exception("查询失败"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 获取记录详情（含转写文本和质检结果）
     */
    suspend fun getRecordDetail(recordId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/records/$recordId")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) Result.success(body)
                else Result.failure(Exception("查询失败"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 测试服务器连接
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/records?page=1&page_size=1")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success("连接成功")
            } else {
                Result.failure(Exception("认证失败，请检查 API Key"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("连接失败: ${e.message}"))
        }
    }
}
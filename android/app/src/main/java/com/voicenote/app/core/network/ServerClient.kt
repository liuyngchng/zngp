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
 * 服务器客户端 — 用户名密码登录，自动换取并续期 JWT token。
 * token 由服务端滑动续期（每次请求返回新 token），只要持续使用就不过期。
 */
class ServerClient(
    private val baseUrl: String = "http://localhost:8080",
    private val username: String = "admin",
    private val password: String = ""
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // 内存中的 token 缓存
    @Volatile private var cachedToken: String? = null

    companion object {
        private const val TAG = "ServerClient"
    }

    /**
     * 登录，获取 JWT token
     */
    private suspend fun login(): String = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(mapOf(
            "username" to username,
            "password" to password
        )).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/auth/login")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (response.isSuccessful) {
            val token = JsonParser.parseString(body).asJsonObject.get("token")?.asString
                ?: throw Exception("登录响应缺少 token")
            token
        } else {
            val error = try {
                JsonParser.parseString(body).asJsonObject.get("error")?.asString
            } catch (_: Exception) { "HTTP ${response.code}" }
            throw Exception(error ?: "登录失败")
        }
    }

    /**
     * 执行带认证的请求。自动处理 token 获取、续期、过期重登。
     */
    private suspend fun authedRequest(build: (token: String) -> Request): okhttp3.Response =
        withContext(Dispatchers.IO) {
            // 确保有 token
            var token = cachedToken ?: login().also { cachedToken = it }

            var response = client.newCall(build(token)).execute()

            // token 过期（401），重新登录后重试一次
            if (response.code == 401) {
                response.close()
                token = login().also { cachedToken = it }
                response = client.newCall(build(token)).execute()
            }

            // 服务端返回新 token（滑动续期），更新缓存
            val newToken = response.header("X-New-Token")
            if (!newToken.isNullOrBlank()) {
                cachedToken = newToken
            }

            response
        }

    /**
     * 上传录音文件 + 元数据
     */
    suspend fun upload(audioFile: File, metadata: UploadMetadata): Result<UploadResult> =
        withContext(Dispatchers.IO) {
            try {
                val metadataJson = gson.toJson(metadata)

                val response = authedRequest { token ->
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("metadata", metadataJson)
                        .addFormDataPart("audio", audioFile.name,
                            audioFile.asRequestBody("audio/wav".toMediaType()))
                        .build()

                    Request.Builder()
                        .url("$baseUrl/api/records")
                        .addHeader("Authorization", "Bearer $token")
                        .post(requestBody)
                        .build()
                }

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
                val response = authedRequest { token ->
                    Request.Builder()
                        .url("$baseUrl/api/records/$recordId/transcript-status")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                }

                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success(gson.fromJson(body, TranscriptStatus::class.java))
                } else {
                    Result.failure(Exception("查询失败"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 测试服务器连接（登录 + 拉取一条记录）
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = authedRequest { token ->
                Request.Builder()
                    .url("$baseUrl/api/records?page=1&page_size=1")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            if (response.isSuccessful) {
                Result.success("连接成功")
            } else {
                Result.failure(Exception("认证失败，请检查用户名密码"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("连接失败: ${e.message}"))
        }
    }
}
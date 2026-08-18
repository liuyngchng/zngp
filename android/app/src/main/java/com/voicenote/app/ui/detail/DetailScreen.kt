package com.voicenote.app.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.voicenote.app.domain.model.VoiceRecord
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recordId: Long,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(recordId) { viewModel.loadRecord(recordId) }
    LaunchedEffect(uiState.isDeleted) { if (uiState.isDeleted) onBack() }
    DisposableEffect(Unit) { onDispose { viewModel.stopPlayback() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("录音详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::showDeleteConfirmation) {
                        Icon(Icons.Default.Delete, contentDescription = "删除记录",
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.record == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("记录未找到", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            else -> {
                val record = uiState.record!!
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 信息区
                    InfoSection(record)
                    // 播放控制
                    AudioPlayerSection(
                        record = record,
                        playbackState = uiState.playbackState,
                        playbackProgress = uiState.playbackProgress,
                        playbackPositionFormatted = uiState.playbackPositionFormatted,
                        playbackDurationFormatted = uiState.playbackDurationFormatted,
                        onPlayPause = viewModel::play,
                        onSeek = viewModel::seekTo,
                        onSkipBack = viewModel::skipBackward,
                        onSkipForward = viewModel::skipForward,
                        onShare = viewModel::getShareIntent
                    )
                    // 上传区
                    UploadSection(
                        isUploading = uiState.isUploading,
                        uploadProgress = uiState.uploadProgress,
                        uploadMessage = uiState.uploadMessage,
                        transcriptStatus = uiState.transcriptStatus,
                        transcriptText = uiState.transcriptText,
                        onUpload = viewModel::showUploadDialog
                    )
                }
            }
        }
    }

    // 删除确认
    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("删除确认") },
            text = { Text("确定要删除这条录音记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = viewModel::deleteRecord, enabled = !uiState.isDeleting) {
                    if (uiState.isDeleting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteConfirmation) { Text("取消") } }
        )
    }

    // 上传对话框
    if (uiState.showUploadDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUploadDialog,
            title = { Text("上传到服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.inspectorName,
                        onValueChange = viewModel::updateInspectorName,
                        label = { Text("安检员姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uiState.customerName,
                        onValueChange = viewModel::updateCustomerName,
                        label = { Text("客户姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uiState.customerAddress,
                        onValueChange = viewModel::updateCustomerAddress,
                        label = { Text("客户地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUploadDialog()
                    viewModel.uploadToServer()
                }, enabled = !uiState.isUploading) {
                    Text("确认上传")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUploadDialog) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InfoSection(record: VoiceRecord) {
    val timeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (record.title.isNotBlank()) {
            Text(record.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (record.memo.isNotBlank()) {
            Text(record.memo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Text("时间: ${record.startTime?.let { timeFormatter.format(it) } ?: "未知"}", style = MaterialTheme.typography.bodySmall)
        val duration = record.endTime?.let { end -> end.epochSecond - record.startTime.epochSecond } ?: 0L
        Text("时长: ${DetailViewModel.formatTime(duration)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AudioPlayerSection(
    record: VoiceRecord,
    playbackState: PlaybackState,
    playbackProgress: Float,
    playbackPositionFormatted: String,
    playbackDurationFormatted: String,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onShare: () -> android.content.Intent?
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("音频播放", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))

        Slider(
            value = playbackProgress,
            onValueChange = onSeek,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(playbackPositionFormatted, style = MaterialTheme.typography.bodySmall)
            Text(playbackDurationFormatted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSkipBack) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "后退15秒", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(
                    imageVector = if (playbackState == PlaybackState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState == PlaybackState.PLAYING) "暂停" else "播放",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onSkipForward) {
                Icon(Icons.Default.SkipNext, contentDescription = "快进15秒", modifier = Modifier.size(32.dp))
            }
        }
        TextButton(
            onClick = {
                val intent = onShare()
                if (intent != null) {
                    // 获取 context 的简化方式：通过当前 Composable 的 LocalContext
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("分享")
        }
    }
}

@Composable
private fun UploadSection(
    isUploading: Boolean,
    uploadProgress: Float,
    uploadMessage: String,
    transcriptStatus: String,
    transcriptText: String,
    onUpload: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("上传到服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("转写和质检由服务端完成", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(12.dp))

        if (isUploading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(uploadMessage, style = MaterialTheme.typography.bodyMedium)
            if (uploadProgress > 0f) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
            }
        } else if (uploadMessage.isBlank()) {
            Button(
                onClick = onUpload,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("上传到服务器")
            }
        } else {
            Text(uploadMessage, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }

        // 转写状态
        if (transcriptStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("转写状态: $transcriptStatus", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }

        // 转写文本
        if (transcriptText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            SelectionContainer {
                Text(transcriptText, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
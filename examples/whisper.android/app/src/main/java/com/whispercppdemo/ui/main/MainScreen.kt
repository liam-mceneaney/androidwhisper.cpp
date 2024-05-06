package com.whispercppdemo.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment

import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.whispercppdemo.R
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import java.io.File


@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    // Existing state observations
    val context = LocalContext.current
    val canTranscribeState = viewModel.canTranscribe.observeAsState(initial = false)
    val isRecordingState = viewModel.isRecording.observeAsState(initial = false)
    val isStreamingState = viewModel.isStreaming.observeAsState(initial = false)
    val samplesState = viewModel.samples.observeAsState(initial = emptyList())
    val selectedSampleState = viewModel.selectedSample.observeAsState(initial = "")
    val messageLogState = viewModel.dataLog.observeAsState(initial = "")
    val processingTimeMessage = viewModel.processingTimeMessage.observeAsState(initial = "")

    // Observe the selected directory state
    val selectedDirectoryState = viewModel.selectedDirectory.observeAsState(initial = null)

    MainScreen(
        canTranscribe = canTranscribeState.value,
        isRecording = isRecordingState.value,
        isStreaming = isStreamingState.value,
        messageLog = messageLogState.value,
        processingTimeMessage = processingTimeMessage.value,
        onBenchmarkTapped = viewModel::benchmark,
        onTranscribeSampleTapped = viewModel::transcribeSample,
        onRecordTapped = viewModel::toggleRecord,
        onStreamTapped = viewModel::toggleStream,
        samples = samplesState.value,
        selectedSample = selectedSampleState.value,
        selectedDirectory = selectedDirectoryState.value,
        onSampleSelected = { sampleName -> viewModel.onSampleSelected(sampleName) },
        onDirectorySelected = { directory -> viewModel.onDirectorySelected(directory) },
        onTranscribeAllTapped = viewModel::onTranscribeAllTapped,
        onExportTapped = { viewModel.onExportAllTranscriptionsToDownloads(context) }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    canTranscribe: Boolean,
    isRecording: Boolean,
    isStreaming: Boolean,
    messageLog: String,
    processingTimeMessage: String,
    onBenchmarkTapped: () -> Unit,
    onTranscribeSampleTapped: () -> Unit,
    onRecordTapped: () -> Unit,
    onStreamTapped: () -> Unit,
    samples: List<String>,
    selectedSample: String?,
    selectedDirectory: String?,
    onDirectorySelected: (String) -> Unit,
    onSampleSelected: (String) -> Unit,
    onTranscribeAllTapped: () -> Unit,
    onExportTapped: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {

            Column(verticalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    BenchmarkButton(enabled = canTranscribe, onClick = onBenchmarkTapped)
                    //TranscribeSampleButton(enabled = canTranscribe, onClick = onTranscribeSampleTapped)
                }
                RecordSection(
                    enabled = canTranscribe,
                    isRecording = isRecording,
                    processingTimeMessage = processingTimeMessage,
                    onClick = onRecordTapped
                )
                StreamButton(
                    enabled = canTranscribe,
                    isStreaming = isStreaming,
                    onClick = onStreamTapped
                )

                SampleSelector(
                    selectedDirectory = selectedDirectory,
                    selectedSample = selectedSample ?: "select a Sample",  // Use placeholder if null
                    onSampleSelected = onSampleSelected,
                    onDirectorySelected = onDirectorySelected
                )

                TranscribeAllSamplesButton(enabled = canTranscribe, onClick = onTranscribeAllTapped)
                ExportButton(enabled = true, onClick = onExportTapped)
            }
            MessageLog(messageLog)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleSelector(
    selectedDirectory: String?,
    selectedSample: String,
    onSampleSelected: (String) -> Unit,
    onDirectorySelected: (String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(listOf<String>()) }
    var selectedSampleState by remember { mutableStateOf(selectedSample) }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            Log.d("SampleSelector", "Selected URI: $uri")
            onDirectorySelected(uri.toString())  // Store the URI as a string if needed
            samples = getSampleFiles(context, uri)
        } else {
            Log.e("SampleSelector", "No URI obtained")
        }
    }
    LaunchedEffect(selectedDirectory) {
        selectedDirectory?.let {
            try {
                val uri = Uri.parse(it)
                Log.d("SampleSelector", "Using stored URI: $uri")
                samples = getSampleFiles(context, uri)
                if (selectedSampleState.isEmpty() && samples.isNotEmpty()) {
                    selectedSampleState = samples.first()
                    onSampleSelected(selectedSampleState)
                }
            } catch (e: IllegalArgumentException) {
                Log.e("SampleSelector", "Invalid URI: $it", e)
            }
        }
    }

    // UI for directory selection
    Row {
        Button(
            onClick = {
                directoryPickerLauncher.launch(null)
            },
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Text("Select Directory")
        }
        TextField(
            readOnly = true,
            value = selectedDirectory ?: "No directory selected",
            onValueChange = { },
            modifier = Modifier.weight(1f)
        )
    }

    // Dropdown for sample selection
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        TextField(
            readOnly = true,
            value = selectedSampleState,
            onValueChange = { },
            label = { Text("Select Sample") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            samples.forEach { sample ->
                DropdownMenuItem(
                    text = { Text(sample) },
                    onClick = {
                        selectedSampleState = sample
                        onSampleSelected(sample)
                        expanded = false
                    }
                )
            }
        }
    }
}



private fun getSampleFiles(context: Context, uri: Uri): List<String> {
    val documentFile = DocumentFile.fromTreeUri(context, uri)
    return documentFile?.listFiles()?.mapNotNull { it.name } ?: emptyList()

}



@Composable
fun MessageLog(log: String) {
    SelectionContainer {
        Text(modifier = Modifier.verticalScroll(rememberScrollState()), text = log)
    }
}

@Composable
fun BenchmarkButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Benchmark")
    }
}

@Composable
fun TranscribeSampleButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Transcribe 1st sample")
    }
}

@Composable
fun TranscribeAllSamplesButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Transcribe All")
    }
}
@Composable
fun ExportButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Export transcriptions")
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RecordButton(enabled: Boolean, isRecording: Boolean, onClick: () -> Unit) {
    val micPermissionState = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO,
        onPermissionResult = { granted ->
            if (granted) {
                onClick()
            }
        }
    )
    Button(onClick = {
        if (micPermissionState.status.isGranted) {
            onClick()
        } else {
            micPermissionState.launchPermissionRequest()
        }
    }, enabled = enabled) {
        Text(
            if (isRecording) {
                "Stop recording"
            } else {
                "Start recording"
            }
        )
    }
}
@Composable
fun RecordSection(enabled: Boolean, isRecording: Boolean, processingTimeMessage: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
//        RecordButton(
//            enabled = enabled,
//            isRecording = isRecording,
//            onClick = onClick
//        )
        Spacer(Modifier.width(8.dp))
        Text(text = processingTimeMessage)
    }
}
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StreamButton(enabled: Boolean, isStreaming: Boolean, onClick: () -> Unit) {
    val micPermissionState = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO,
        onPermissionResult = { granted ->
            if (granted) {
                onClick()
            }
        }
    )
    Button(onClick = {
        if (micPermissionState.status.isGranted) {
            onClick()
        } else {
            micPermissionState.launchPermissionRequest()
        }
    }, enabled = enabled) {
        Text(
            if (isStreaming) {
                "Stop streaming"
            } else {
                "Start streaming"
            }
        )
    }
}
package com.whispercppdemo.ui.main

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.PackageManagerCompat.LOG_TAG
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import com.whispercppdemo.recorder.Recorder
import android.os.Environment
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel



private const val TAG = "MainScreenViewModel" //logging tag


class MainScreenViewModel(application: Application,
) : AndroidViewModel(application) {

    private val _samples = MutableLiveData<List<String>>(listOf(/* Sample names */))
    val samples: LiveData<List<String>> = _samples

    private val _selectedSample = MutableLiveData<String>()
    val selectedSample: LiveData<String> = _selectedSample

    private val _canTranscribe = MutableLiveData(false)
    val canTranscribe: LiveData<Boolean> = _canTranscribe

    private val _dataLog = MutableLiveData("")
    val dataLog: LiveData<String> = _dataLog

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _isStreaming = MutableLiveData(false)
    val isStreaming: LiveData<Boolean> = _isStreaming

    private val _processingTimeMessage = MutableLiveData("")
    val processingTimeMessage: LiveData<String> = _processingTimeMessage


    private var isActive = false

//    private var audioBuffer = mutableListOf<Float>()

    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")
    private val transcriptionsPath = File(application.filesDir, "transcriptions")
    private val recorder: Recorder = Recorder()
    private var whisperContext: WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null

    data class AudioState(
        var isCapturing: Boolean = false,
        var isTranscribing: Boolean = false,
        var nSamples: Int = 0,
        var audioBufferF32: MutableList<Float> = mutableListOf()
    )




    init {
        viewModelScope.launch {
            printSystemInfo()
            loadData()
        }
    }


    private suspend fun printSystemInfo() {
        printMessage(String.format("System Info: %s\n", WhisperContext.getSystemInfo()))
    }

    @SuppressLint("RestrictedApi")
    private suspend fun loadData() {
        printMessage("Loading data...\n")
        try {
            copyAssets()
            loadBaseModel()
            _canTranscribe.value = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        _dataLog.value += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        transcriptionsPath.mkdirs()
        //application.copyData("models", modelsPath, ::printMessage)
        val appContext = getApplication<Application>()
        appContext.copyData("samples", samplesPath, ::printMessage)
        printMessage("All data copied to working directory.\n")
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        printMessage("Loading model...\n")
        val models = getApplication<Application>().assets.list("models/")
        if (models != null) {
            whisperContext =
                WhisperContext.createContextFromAsset(getApplication<Application>().assets, "models/" + models[0])
            printMessage("Loaded model ${models[0]}.\n")
        }

        //val firstModel = modelsPath.listFiles()!!.first()
        //whisperContext = WhisperContext.createContextFromFile(firstModel.absolutePath)
    }

    fun benchmark() = viewModelScope.launch {
        runBenchmark(6)
    }

    fun transcribeSample() = viewModelScope.launch {
        transcribeAudio(getFirstSample())
    }
    fun transcribeAllSamples() = viewModelScope.launch {
        val sampleFiles = samplesPath.listFiles()
        if (sampleFiles != null) {
            for (sampleFile in sampleFiles) {
                transcribeAudio(sampleFile)
            }
        } else {
            Log.e(LOG_TAG, "No files found in the directory.")
        }
    }

    fun exportFileToDownloads(context: Context, fileName: String, folderName: String = "Transcriptions") {
        try {
            // Source file in the app's internal storage
            val sourceFile = File(context.filesDir, "transcriptions/$fileName")

            // Target location within a specific folder in the Downloads directory
            val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetFolder = File(downloadsPath, folderName) // Specify the folder name here
            val targetFile = File(targetFolder, fileName)

            // Ensure the target folder exists in the Downloads directory
            if (!targetFolder.exists()) {
                targetFolder.mkdirs()
            }

            // Initialize file channels for copying
            val inChannel: FileChannel = FileInputStream(sourceFile).channel
            val outChannel: FileChannel = FileOutputStream(targetFile).channel
            inChannel.transferTo(0, inChannel.size(), outChannel)

            // Close the channels
            inChannel.close()
            outChannel.close()

            // Notify the user or log success
            Log.d("FileExport", "File successfully exported to $folderName in Downloads")
        } catch (e: Exception) {
            // Handle any exceptions
            Log.e("FileExport", "Error exporting file to $folderName: ${e.message}")
        }
    }


    private suspend fun runBenchmark(nthreads: Int) {
        val canTranscribeNow = withContext(Dispatchers.Main) {
            _canTranscribe.value ?: false
        }

        if (!canTranscribeNow) {
            return
        }

        withContext(Dispatchers.Main) {
            _canTranscribe.value = false
        }

        printMessage("Running benchmark. This will take minutes...\n")
        whisperContext?.benchMemory(nthreads)?.let { printMessage(it) }
        printMessage("\n")
        whisperContext?.benchGgmlMulMat(nthreads)?.let { printMessage(it) }

        withContext(Dispatchers.Main) {
            _canTranscribe.value = true
        }
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        samplesPath.listFiles()!!.first()
    }
    private suspend fun getSamples(): List<File> = withContext(Dispatchers.IO) {
        samplesPath.listFiles()?.filter { it.isFile } ?: emptyList()
    }
    private suspend fun findSampleFileByName(sampleName: String): File? = withContext(Dispatchers.IO) {
        return@withContext samplesPath.listFiles()?.find { it.nameWithoutExtension == sampleName }
    }
    fun onSampleSelected(sample: String) {
        _selectedSample.value = sample
        transcribeSelectedSample(sample)
    }

    fun onTranscribeAllTapped() {
        transcribeAllSamples()
    }
    private fun transcribeSelectedSample(sampleName: String) = viewModelScope.launch {
        val sampleFile = findSampleFileByName(sampleName)
        if (sampleFile != null) {
            transcribeAudio(sampleFile)
        } else {
            Log.e(LOG_TAG, "no file found")
        }
    }


    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        stopPlayback()
        startPlayback(file)
        return@withContext com.whispercppdemo.media.decodeWaveFile(file)
    }

//    private suspend fun streamAudioSamples(shortArray: ShortArray): FloatArray = withContext(Dispatchers.IO) {
//        stopPlayback()
//        return@withContext com.example.csct_gui_demo.media.processAudioChunk()
//    }


    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(getApplication<Application>(), file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    private val _transcriptionText = MutableLiveData<String>("")
    val transcriptionText: LiveData<String> = _transcriptionText

    @SuppressLint("RestrictedApi")

    suspend fun saveTranscriptionToFile(text: String, filename: String) {
        withContext(Dispatchers.IO) {
            val outputFile = File(transcriptionsPath, filename)
            outputFile.bufferedWriter().use { out ->
                out.write(text)
            }
        }
    }
    suspend fun logProcessingTime(sampleFile: File, sampleTimeLength: Long, elapsedProcessingTime: Long) {
        withContext(Dispatchers.IO) {
            val logFileName = "${sampleFile.nameWithoutExtension}_processingTime.txt"
            val logFile = File(transcriptionsPath, logFileName)

            logFile.bufferedWriter().use { out ->
                out.write("Sample Time Length: $sampleTimeLength ms\n")
                out.write("Elapsed Processing Time: $elapsedProcessingTime ms\n")
            }
        }
    }
    suspend fun exportAllTranscriptionsToDownloads(context: Context) = withContext(Dispatchers.IO) {
        // Directory containing the transcription files
        val transcriptionsDir = File(context.filesDir, "transcriptions")

        // Check if the directory exists and is a directory
        if (transcriptionsDir.exists() && transcriptionsDir.isDirectory) {
            // List all files in the directory
            val files = transcriptionsDir.listFiles()
            files?.forEach { file ->
                // Export each file to the Downloads directory
                exportFileToDownloads(context, file.name)
            }
        }
    }
    fun onexportAllTranscriptionsToDownloads(context: Context) {
        viewModelScope.launch {
            // Assuming exportAllTranscriptionsToDownloads is defined elsewhere and is a suspend function
            exportAllTranscriptionsToDownloads(context)
        }
    }

    @SuppressLint("RestrictedApi")
    private suspend fun transcribeAudio(file: File) {
        val canTranscribe = withContext(Dispatchers.Main) {
            _canTranscribe.value ?: false
        }
        if (!canTranscribe) {
            return
        }

        try {
            printMessage("Reading wave samples... ")
            val data = readAudioSamples(file)
            printMessage("${data.size / (16000 / 1000)} ms\n")
            printMessage("Transcribing data...\n")
            val start = System.currentTimeMillis()
            val text = whisperContext?.transcribeData(data)
            //text to be processed and then sent to SQL
            if(text != null) {
                withContext(Dispatchers.Main) {
                    // Update transcriptionText LiveData with the new transcription
                    _transcriptionText.value = text!!
                    if (isRecording.value != true) {
                        Log.i(TAG, "Text: $text")
                    }
                }
                val transcriptionFileName = "${file.nameWithoutExtension}_transcription.txt"
                saveTranscriptionToFile(text, transcriptionFileName)
            }

            val elapsed = System.currentTimeMillis() - start
            printMessage("Done ($elapsed ms): $text\n")
            logProcessingTime(file, start, elapsed)
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }

        _canTranscribe.value = true
    }
    //streamTranscribe???
    private var lastProcessedTimestamp: Long = 0 // Keep track of the last processed audio timestamp
    private val audioState = AudioState()
    private var MAX_AUDIO_SEC = 30
    private var SAMPLE_RATE = 16000
    private var streamingStartTime: Long = 0
    private var totalProcessingTime: Long = 0
    //16*1024 * seconds you want for a chunk
    private val chunkSize = 16*1024*5

    private fun startStreaming() {
        if (_isStreaming.value != true) {
            Log.d(TAG, "Starting streaming 2 electric boogaloo...")
            _isStreaming.value = true

//            audioBuffer.clear()
            audioState.isCapturing = true
            audioState.audioBufferF32.clear()
            audioState.nSamples = 0

            lastProcessedTimestamp = System.currentTimeMillis() // Resetting the timestamp
            streamingStartTime = System.currentTimeMillis()
            // onDataReceived to handle buffering and processing audio data
            val onDataReceived = object : Recorder.AudioDataReceivedListener {
                override fun onAudioDataReceived(data: FloatArray) {
                    // Add incoming data to the buffer
//                    audioBuffer.addAll(data.toList())
                    if (!audioState.isCapturing) {
                        Log.d(TAG, "Not capturing, ignoring audio")
                        return
                    }
                    if (audioState.nSamples + data.size > MAX_AUDIO_SEC * SAMPLE_RATE) {
                        Log.d(TAG, "Too much audio data, ignoring")
//                        toggleStream()
                        //empty the buffer
                        audioState.audioBufferF32.clear()
                        audioState.nSamples = 0
                        return
                    }
                    audioState.audioBufferF32.addAll(data.toList())
                    audioState.nSamples += data.size
                    // Process the buffer in chunks
                    processBufferedAudioChunks()
                }
            }

            // Start streaming with the onDataReceived listener
            recorder.startStreaming(onDataReceived) { e ->
                Log.e(TAG, "Error during streaming: ${e.localizedMessage}", e)
                _isStreaming.postValue(false)
            }
        } else {
            Log.i(TAG, "Streaming is already active.")
        }
    }
    private fun processBufferedAudioChunks() {
        if (audioState.isTranscribing) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioState.isTranscribing = true
                while (audioState.audioBufferF32.size >= chunkSize) {
                    val processingStartTime = System.currentTimeMillis()
                    val chunkToProcess = audioState.audioBufferF32.take(chunkSize).toFloatArray()


                    val textChunk = whisperContext?.streamTranscribeData(chunkToProcess) ?: ""
                    Log.i(TAG, "Decoded Audio Chunk Text = $textChunk")
                    val processingEndTime = System.currentTimeMillis()
                    totalProcessingTime += (processingEndTime - processingStartTime)

                    val recordingTime = (System.currentTimeMillis() - streamingStartTime) / 1000.0
                    val processingTime = (processingEndTime - processingStartTime) / 1000.0
                    withContext(Dispatchers.Main) {
                        val currentText = _transcriptionText.value ?: ""
                        _transcriptionText.value = currentText + textChunk
                        val recordingTime = (System.currentTimeMillis() - streamingStartTime) / 1000.0
                        val cumulativeProcessingTime = totalProcessingTime / 1000.0
                        val realTimeFactor = cumulativeProcessingTime / recordingTime
                        val timeInfo = "Recording time: ${"%.3f".format(recordingTime)} s, " +
                                "Processing time: ${"%.3f".format(cumulativeProcessingTime)} s, " +
                                "Real-time factor: ${"%.3f".format(realTimeFactor)}"
                        Log.i(TAG,"$timeInfo")
                        printMessage(textChunk!!)
                        _processingTimeMessage.value = timeInfo
                        Log.i(TAG, "Final Text: ${_transcriptionText.value}")
                    }
                    audioState.audioBufferF32 = audioState.audioBufferF32.drop(chunkSize).toMutableList()
//                        lastProcessedTimestamp = currentTimestamp // Update the last processed timestamp

//                    audioBuffer = audioBuffer.drop(chunkSize).toMutableList()
                }
                audioState.isTranscribing = false
            } catch (e: Exception) {
                Log.e(TAG, "Error during buffer processing: ${e.localizedMessage}", e)
            }
        }
    }
    @SuppressLint("RestrictedApi")
    fun toggleRecord() = viewModelScope.launch {
        try {
            if (_isRecording.value == true) {
                recorder.stopRecording()
                _isRecording.value = false
                recordedFile?.let {
                    transcribeAudio(it)
                }
            } else {
                stopPlayback()
                val file = getTempFileForRecording()
                recorder.startRecording(file) { e ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            _isRecording.value = false
                        }
                    }
                }
                _isRecording.value = true
                recordedFile = file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            _isRecording.value = false
        }
    }

    fun toggleStream() = viewModelScope.launch {
        if (_isStreaming.value == true) {
            Log.d(TAG, "Stopping streaming...")
            recorder.stopRecording()
            _isStreaming.value = false
            Log.d(TAG, "Streaming stopped")
        } else {
            Log.d(TAG, "Starting streaming...")
            stopPlayback()
            startStreaming()
        }
    }


    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        File.createTempFile("recording", "wav")
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
            stopPlayback()
        }
    }
    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }


}
private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        Log.v(TAG, "Processing $assetPath...")
        val destination = File(destDir, name)
        Log.v(TAG, "Copying $assetPath to $destination...")
        printMessage("Copying $name...\n")
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.v(TAG, "Copied $assetPath to $destination")


    }
}

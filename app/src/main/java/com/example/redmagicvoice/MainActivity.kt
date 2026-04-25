package com.example.redmagicvoice

import android.Manifest
import android.animation.ValueAnimator
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * RedMagicVoice - 专业离线语音助手
 * 版本：v 1.0.0
 * 开发作者：许怀光 (墨染夜殇)
 * 优化：支持多线程并行转录、异步UI更新、自动同名导出
 */
class MainActivity : AppCompatActivity(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    
    private lateinit var resultText: EditText
    private lateinit var statusText: TextView
    private lateinit var recognizeButton: MaterialButton
    private lateinit var statusDot: View
    private lateinit var waveformContainer: LinearLayout
    private lateinit var resultScroll: ScrollView
    private lateinit var copyButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var footerInfo: TextView

    private lateinit var pickFileButton: MaterialButton
    private lateinit var openFolderButton: MaterialButton
    private lateinit var fileStatusText: TextView
    private lateinit var fileProgressBar: ProgressBar

    private val fullText = StringBuilder()
    private val animators = mutableListOf<ValueAnimator>()

    // 使用固定大小线程池，充分利用多核 CPU (限制为2个并发以保证稳定性)
    private val transcriptionExecutor = Executors.newFixedThreadPool(2)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) initModel()
        else statusText.text = "请开启录音权限"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        applyWindowInsets()
        checkPermissionAndInit()
    }

    private fun initViews() {
        resultText = findViewById(R.id.result_text)
        statusText = findViewById(R.id.status_text)
        recognizeButton = findViewById(R.id.recognize_button)
        statusDot = findViewById(R.id.status_dot)
        waveformContainer = findViewById(R.id.waveform_progress)
        resultScroll = findViewById(R.id.result_scroll)
        copyButton = findViewById(R.id.copy_button)
        clearButton = findViewById(R.id.clear_button)
        saveButton = findViewById(R.id.save_button)
        footerInfo = findViewById(R.id.footer_info)
        pickFileButton = findViewById(R.id.pick_file_button)
        openFolderButton = findViewById(R.id.open_folder_button)
        fileStatusText = findViewById(R.id.file_status_text)
        fileProgressBar = findViewById(R.id.file_process_bar)
    }

    private fun setupClickListeners() {
        recognizeButton.setOnClickListener { toggleRecognition() }
        copyButton.setOnClickListener { copyToClipboard() }
        clearButton.setOnClickListener { clearResults() }
        saveButton.setOnClickListener { saveToFile() }
        openFolderButton.setOnClickListener { openDownloadsFolder() }
        footerInfo.setOnClickListener { copyWeChatAndOpen() }
        pickFileButton.setOnClickListener { batchPickerLauncher.launch("audio/*") }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun checkPermissionAndInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initModel()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initModel() {
        statusText.text = "正在载入复古书页..."
        statusDot.setBackgroundColor(Color.YELLOW)
        
        StorageService.unpack(this, "model-cn", "model",
            { model: Model ->
                this.model = model
                runOnUiThread {
                    statusText.text = "语音助手就绪"
                    statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_light_blue))
                    recognizeButton.isEnabled = true
                    recognizeButton.text = "开始识别"
                    pickFileButton.isEnabled = true
                }
            },
            { e -> runOnUiThread { statusText.text = "载入失败: ${e.message}" } }
        )
    }

    private val batchPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            processFilesMultiThreaded(uris)
        }
    }

    /**
     * 多线程并行处理逻辑
     */
    private fun processFilesMultiThreaded(uris: List<Uri>) {
        if (model == null) return
        
        val totalCount = uris.size
        val completedCount = AtomicInteger(0)
        
        fileProgressBar.visibility = View.VISIBLE
        fileProgressBar.progress = 0
        pickFileButton.isEnabled = false
        fileStatusText.text = "正在准备并行转录任务..."

        uris.forEach { uri ->
            transcriptionExecutor.execute {
                val fileName = getFileName(uri)
                val transcription = decodeAndTranscribe(uri)
                
                if (transcription.isNotEmpty()) {
                    runOnUiThread {
                        resultText.append("\n[文件 $fileName 转录完成]:\n$transcription\n")
                        resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                    saveToDownloads(fileName.substringBeforeLast("."), transcription)
                }
                
                // 任务完成计数
                val currentDone = completedCount.incrementAndGet()
                runOnUiThread {
                    fileProgressBar.progress = ((currentDone.toFloat() / totalCount) * 100).toInt()
                    fileStatusText.text = "已完成: $currentDone / $totalCount"
                    
                    if (currentDone == totalCount) {
                        finishBatchTasks()
                    }
                }
            }
        }
    }

    private fun finishBatchTasks() {
        fileStatusText.text = "批量任务已完成，TXT已导出"
        fileProgressBar.visibility = View.INVISIBLE
        pickFileButton.isEnabled = true
        Toast.makeText(this, "全部音频已转录并保存", Toast.LENGTH_SHORT).show()
    }

    private fun decodeAndTranscribe(uri: Uri): String {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val sb = StringBuilder()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                extractor.setDataSource(fd.fileDescriptor)
            }

            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return ""

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            // 每个线程拥有独立的 Recognizer，共享同一个 Model
            val rec = Recognizer(model!!, sampleRate.toFloat())
            val info = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)!!
                    val rawChunk = ByteArray(info.size)
                    outBuffer.get(rawChunk)
                    outBuffer.clear()

                    val finalChunk = if (channelCount > 1) {
                        val mono = ByteArray(rawChunk.size / channelCount)
                        for (i in 0 until mono.size / 2) {
                            mono[i * 2] = rawChunk[i * 2 * channelCount]
                            mono[i * 2 + 1] = rawChunk[i * 2 * channelCount + 1]
                        }
                        mono
                    } else {
                        rawChunk
                    }

                    if (rec.acceptWaveForm(finalChunk, finalChunk.size)) {
                        val res = JSONObject(rec.result).optString("text").replace(" ", "")
                        if (res.isNotEmpty()) sb.append(res).append("。")
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                }
            }
            val finalRes = JSONObject(rec.finalResult).optString("text").replace(" ", "")
            if (finalRes.isNotEmpty()) sb.append(finalRes).append("。")

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
            extractor.release()
        }
        return sb.toString()
    }

    private fun getFileName(uri: Uri): String {
        var name = "audio_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name
    }

    private fun saveToDownloads(fileName: String, content: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun toggleRecognition() {
        if (speechService != null) {
            speechService?.stop()
            speechService = null
            recognizeButton.text = "开始识别"
            recognizeButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.btn_light_blue))
            stopWaveformAnimation()
            statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_light_blue))
            statusText.text = "识别停止"
        } else {
            try {
                val rec = Recognizer(model!!, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                recognizeButton.text = ""
                recognizeButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.btn_light_green))
                startWaveformAnimation()
                statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_light_green))
                statusText.text = "正在收音..."
            } catch (e: IOException) {
                Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResult(hypothesis: String) {
        val text = JSONObject(hypothesis).optString("text").replace(" ", "")
        if (text.isNotEmpty()) {
            fullText.append(text).append("。\n\n")
            resultText.append(text + "。\n\n")
            resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onPartialResult(hypothesis: String) {}
    override fun onFinalResult(hypothesis: String) {}
    override fun onError(e: Exception) { statusText.text = "出错: ${e.message}" }
    override fun onTimeout() { toggleRecognition() }

    private fun copyToClipboard() {
        val text = resultText.text.toString()
        if (text.isNotEmpty()) {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Text", text))
            Toast.makeText(this, "内容已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearResults() {
        fullText.clear()
        resultText.setText("")
        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
    }

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os ->
                os.write(resultText.text.toString().toByteArray())
                Toast.makeText(this, "文件已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToFile() {
        if (resultText.text.isEmpty()) return
        saveFileLauncher.launch("语音转写_${System.currentTimeMillis()}.txt")
    }

    private fun startWaveformAnimation() {
        waveformContainer.visibility = View.VISIBLE
        waveformContainer.children.forEachIndexed { i, v ->
            val anim = ValueAnimator.ofFloat(1f, 2.5f, 1f).apply {
                duration = 400L + (i * 100L)
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { v.scaleY = it.animatedValue as Float }
            }
            anim.start()
            animators.add(anim)
        }
    }

    private fun stopWaveformAnimation() {
        animators.forEach { it.cancel() }
        animators.clear()
        waveformContainer.visibility = View.GONE
    }

    private fun openDownloadsFolder() {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                val uri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                intent.setDataAndType(uri, "*/*")
                startActivity(Intent.createChooser(intent, "选择文件管理器"))
            } catch (e2: Exception) {
                Toast.makeText(this, "请手动前往'下载'文件夹查看", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyWeChatAndOpen() {
        val wechatId = "MR_XU1230"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("WeChat ID", wechatId)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "微信号 $wechatId 已复制，正在为您跳转微信", Toast.LENGTH_LONG).show()

        try {
            val intent = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "未检测到微信，请手动前往添加", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "跳转失败，请手动打开微信", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWaveformAnimation()
        speechService?.stop()
        speechService?.shutdown()
        transcriptionExecutor.shutdown() // 退出时关闭线程池
    }
}

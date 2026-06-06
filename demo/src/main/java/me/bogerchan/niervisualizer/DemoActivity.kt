package me.bogerchan.niervisualizer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.bogerchan.niervisualizer.converter.AbsAudioDataConverter
import me.bogerchan.niervisualizer.converter.AudioDataConverterFactory
import me.bogerchan.niervisualizer.renderer.IRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleRenderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType1Renderer

class DemoActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_AUDIO_PERMISSION = 1001
        private const val SAMPLING_RATE = 44100
        private const val AUDIO_RECORD_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_RECORD_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private lateinit var surfaceView: SurfaceView
    private var visualizerManager: NierVisualizerManager? = null
    private var audioRecord: AudioRecord? = null
    private var currentStyleIndex = 0

    private val renderers: Array<Array<IRenderer>> by lazy {
        val mikuGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F5D4")
            strokeWidth = 10f
        }

        val hudGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7CFFCB")
            strokeWidth = 8f
        }

        arrayOf(
            arrayOf(ColumnarType1Renderer(mikuGreen)),
            arrayOf(CircleRenderer(hudGreen, false)),
            arrayOf(CircleRenderer(mikuGreen, true)),
            arrayOf(
                ColumnarType1Renderer(mikuGreen),
                CircleRenderer(hudGreen, true)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        hideSystemUi()

        surfaceView = SurfaceView(this).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        setContentView(root)

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                changeStyle()
            }
            true
        }

        ensurePermissionThenStart()
    }

    private fun ensurePermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_CODE_AUDIO_PERMISSION
            )
        } else {
            startMicVisualizer()
        }
    }

    private fun startMicVisualizer() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLING_RATE,
            AUDIO_RECORD_CHANNEL_CONFIG,
            AUDIO_RECORD_FORMAT
        )

        val bufferSize = if (minBufferSize < 4096) 4096 else minBufferSize

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLING_RATE,
            AUDIO_RECORD_CHANNEL_CONFIG,
            AUDIO_RECORD_FORMAT,
            bufferSize
        )

        val record = audioRecord ?: return
        record.startRecording()

        val converter: AbsAudioDataConverter =
            AudioDataConverterFactory.getConverterByAudioRecord(record)

        visualizerManager?.release()

        visualizerManager = NierVisualizerManager().apply {
            init(object : NierVisualizerManager.NVDataSource {
                private val buffer = ByteArray(512)

                override fun getDataSamplingInterval(): Long = 0L

                override fun getDataLength(): Int = buffer.size

                override fun fetchFftData(): ByteArray? = null

                override fun fetchWaveData(): ByteArray? {
                    converter.convertWaveDataTo(buffer)
                    return buffer
                }
            })
        }

        useStyle(currentStyleIndex)
    }

    private fun changeStyle() {
        currentStyleIndex++
        useStyle(currentStyleIndex)
    }

    private fun useStyle(index: Int) {
        visualizerManager?.start(surfaceView, renderers[index % renderers.size])
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        visualizerManager?.resume()
    }

    override fun onPause() {
        super.onPause()
        visualizerManager?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()

        visualizerManager?.release()
        visualizerManager = null

        audioRecord?.apply {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        audioRecord = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMicVisualizer()
            } else {
                Toast.makeText(this, "需要麦克风权限才能显示音乐可视化", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}

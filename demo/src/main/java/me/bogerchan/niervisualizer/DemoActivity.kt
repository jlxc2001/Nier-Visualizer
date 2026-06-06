package me.bogerchan.niervisualizer

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var preferences: SharedPreferences

    private var settingsPanel: View? = null
    private var visualizerManager: NierVisualizerManager? = null
    private var audioRecord: AudioRecord? = null

    private var currentStyleIndex = 0
    private var micSensitivity = 1.0f
    private var strokeWidth = 10f
    private var primaryColor = Color.parseColor("#00F5D4")
    private var secondaryColor = Color.parseColor("#7CFFCB")

    private var renderers: Array<Array<IRenderer>> = arrayOf()

    private val handler = Handler(Looper.getMainLooper())
    private var lastTapTime = 0L
    private var pendingSingleTap: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = getSharedPreferences("hud_visualizer_settings", MODE_PRIVATE)
        loadSettings()

        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        hideSystemUi()

        renderers = createRenderers()

        surfaceView = SurfaceView(this).apply {
            setZOrderOnTop(false)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        setContentView(rootLayout)

        val tapListener = View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                handleTap()
            }
            true
        }

        rootLayout.setOnTouchListener(tapListener)
        surfaceView.setOnTouchListener(tapListener)

        createSettingsPanel()
        ensurePermissionThenStart()
    }

    private fun loadSettings() {
        micSensitivity = preferences.getFloat("micSensitivity", 1.0f)
        strokeWidth = preferences.getFloat("strokeWidth", 10f)
        primaryColor = preferences.getInt("primaryColor", Color.parseColor("#00F5D4"))
        secondaryColor = preferences.getInt("secondaryColor", Color.parseColor("#7CFFCB"))
        currentStyleIndex = preferences.getInt("styleIndex", 0)
    }

    private fun saveSettings() {
        preferences.edit()
            .putFloat("micSensitivity", micSensitivity)
            .putFloat("strokeWidth", strokeWidth)
            .putInt("primaryColor", primaryColor)
            .putInt("secondaryColor", secondaryColor)
            .putInt("styleIndex", currentStyleIndex)
            .apply()
    }

    private fun createPaint(color: Int, stroke: Float): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = stroke
        }
    }

    private fun createRenderers(): Array<Array<IRenderer>> {
        return arrayOf<Array<IRenderer>>(
            arrayOf<IRenderer>(
                ColumnarType1Renderer(createPaint(primaryColor, strokeWidth))
            ),
            arrayOf<IRenderer>(
                CircleRenderer(createPaint(primaryColor, strokeWidth), false)
            ),
            arrayOf<IRenderer>(
                CircleRenderer(createPaint(primaryColor, strokeWidth), true)
            ),
            arrayOf<IRenderer>(
                ColumnarType1Renderer(createPaint(primaryColor, strokeWidth)),
                CircleRenderer(createPaint(secondaryColor, strokeWidth), true)
            ),
            arrayOf<IRenderer>(
                ColumnarType1Renderer(createPaint(secondaryColor, strokeWidth)),
                CircleRenderer(createPaint(primaryColor, strokeWidth), false)
            )
        )
    }

    private fun handleTap() {
        if (settingsPanel?.visibility == View.VISIBLE) {
            return
        }

        val now = System.currentTimeMillis()

        if (now - lastTapTime < 320L) {
            pendingSingleTap?.let { handler.removeCallbacks(it) }
            pendingSingleTap = null
            lastTapTime = 0L
            showSettingsPanel()
        } else {
            lastTapTime = now
            val runnable = Runnable {
                changeStyle()
                pendingSingleTap = null
            }
            pendingSingleTap = runnable
            handler.postDelayed(runnable, 340L)
        }
    }

    private fun createSettingsPanel() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
        }

        val cardBackground = GradientDrawable().apply {
            setColor(Color.argb(235, 8, 16, 22))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(180, 0, 245, 212))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }

        val title = TextView(this).apply {
            text = "HUD Visualizer 设置"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val tip = TextView(this).apply {
            text = "双击屏幕打开设置；单击屏幕切换动效"
            textSize = 13f
            setTextColor(Color.parseColor("#A6FFF2"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }

        card.addView(title)
        card.addView(tip)

        val sensitivityLabel = createLabel("麦克风灵敏度：${formatSensitivity()}")
        val sensitivitySeekBar = SeekBar(this).apply {
            max = 290
            progress = ((micSensitivity * 100f).toInt() - 10).coerceIn(0, 290)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    micSensitivity = (progress + 10) / 100f
                    sensitivityLabel.text = "麦克风灵敏度：${formatSensitivity()}"
                    saveSettings()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        card.addView(sensitivityLabel)
        card.addView(sensitivitySeekBar)

        val strokeLabel = createLabel("动效粗细：${strokeWidth.toInt()}")
        val strokeSeekBar = SeekBar(this).apply {
            max = 28
            progress = (strokeWidth.toInt() - 2).coerceIn(0, 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    strokeWidth = (progress + 2).toFloat()
                    strokeLabel.text = "动效粗细：${strokeWidth.toInt()}"
                    updateVisualStyle()
                    saveSettings()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        card.addView(strokeLabel)
        card.addView(strokeSeekBar)

        card.addView(createLabel("颜色预设"))

        val colorRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        colorRow1.addView(createColorButton("初音绿", "#00F5D4", "#7CFFCB"))
        colorRow1.addView(createColorButton("赛博蓝", "#00B7FF", "#7AE7FF"))
        colorRow1.addView(createColorButton("紫粉", "#FF4FD8", "#9B5CFF"))

        val colorRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        colorRow2.addView(createColorButton("琥珀", "#FFB000", "#FF5A1F"))
        colorRow2.addView(createColorButton("纯白", "#FFFFFF", "#BDEEFF"))
        colorRow2.addView(createColorButton("红警", "#FF3030", "#FF8A00"))

        card.addView(colorRow1)
        card.addView(colorRow2)

        card.addView(createLabel("动效控制"))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        actionRow.addView(createButton("下一个动效") {
            changeStyle()
        })

        actionRow.addView(createButton("重置默认") {
            micSensitivity = 1.0f
            strokeWidth = 10f
            primaryColor = Color.parseColor("#00F5D4")
            secondaryColor = Color.parseColor("#7CFFCB")
            currentStyleIndex = 0
            saveSettings()
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show()
            recreate()
        })

        card.addView(actionRow)

        val closeButton = createButton("关闭设置") {
            hideSettingsPanel()
        }

        card.addView(closeButton)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(28)
            rightMargin = dp(28)
        }

        overlay.addView(card, cardParams)

        settingsPanel = overlay
        rootLayout.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun createLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun createButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(80, 0, 245, 212))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.argb(190, 0, 245, 212))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                setMargins(dp(4), dp(6), dp(4), dp(6))
            }
        }
    }

    private fun createColorButton(
        textValue: String,
        primaryHex: String,
        secondaryHex: String
    ): Button {
        return createButton(textValue) {
            primaryColor = Color.parseColor(primaryHex)
            secondaryColor = Color.parseColor(secondaryHex)
            updateVisualStyle()
            saveSettings()
            Toast.makeText(this, "颜色：$textValue", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateVisualStyle() {
        renderers = createRenderers()
        useStyle(currentStyleIndex)
    }

    private fun showSettingsPanel() {
        settingsPanel?.visibility = View.VISIBLE
        settingsPanel?.bringToFront()
        hideSystemUi()
    }

    private fun hideSettingsPanel() {
        settingsPanel?.visibility = View.GONE
        hideSystemUi()
    }

    private fun formatSensitivity(): String {
        return String.format("%.1fx", micSensitivity)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun ensurePermissionThenStart() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
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

        try {
            record.startRecording()
        } catch (e: Exception) {
            Toast.makeText(this, "麦克风启动失败", Toast.LENGTH_LONG).show()
            return
        }

        val converter: AbsAudioDataConverter =
            AudioDataConverterFactory.getConverterByAudioRecord(record)

        visualizerManager?.release()

        visualizerManager = NierVisualizerManager().apply {
            init(object : NierVisualizerManager.NVDataSource {
                private val buffer = ByteArray(512)

                override fun getDataSamplingInterval(): Long {
                    return 16L
                }

                override fun getDataLength(): Int {
                    return buffer.size
                }

                override fun fetchFftData(): ByteArray? {
                    return null
                }

                override fun fetchWaveData(): ByteArray? {
                    converter.convertWaveDataTo(buffer)
                    applyMicSensitivity(buffer)
                    return buffer
                }
            })
        }

        useStyle(currentStyleIndex)
    }

    private fun applyMicSensitivity(data: ByteArray) {
        for (i in data.indices) {
            val unsignedValue = data[i].toInt() and 0xFF
            val centered = unsignedValue - 128
            val amplified = (centered * micSensitivity).toInt() + 128
            val clamped = when {
                amplified < 0 -> 0
                amplified > 255 -> 255
                else -> amplified
            }
            data[i] = clamped.toByte()
        }
    }

    private fun changeStyle() {
        currentStyleIndex++
        saveSettings()
        useStyle(currentStyleIndex)
    }

    private fun useStyle(index: Int) {
        if (renderers.isEmpty()) return
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

        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null

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
            if (
                grantResults.isNotEmpty()
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startMicVisualizer()
            } else {
                Toast.makeText(this, "需要麦克风权限才能显示音乐可视化", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}

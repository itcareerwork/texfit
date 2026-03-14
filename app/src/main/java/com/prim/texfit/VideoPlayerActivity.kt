package com.prim.texfit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.Locale

class VideoPlayerActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var seekBar: SeekBar
    private lateinit var btnStop: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnExerciseMenu: Button
    private lateinit var tvTime: TextView
    private lateinit var tvExerciseCounter: TextView
    private lateinit var circularTimer: CircularProgressIndicator
    private lateinit var layoutTicks: FrameLayout
    private lateinit var layoutCounterContainer: View
    private lateinit var layoutPauseInfo: View
    private lateinit var clickInterceptor: View

    private var videoItemId: String = ""
    private var videoFileName: String = ""
    private var fileNumForDisplay: String = "000"
    private var timings = mutableListOf<SettingsActivity.Timing>()
    private val handler = Handler(Looper.getMainLooper())
    
    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            try {
                if (videoView.isPlaying) {
                    updateUIState()
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        videoView = findViewById(R.id.video_view)
        seekBar = findViewById(R.id.seek_bar)
        btnStop = findViewById(R.id.btn_stop)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnExerciseMenu = findViewById(R.id.btn_exercise_menu)
        tvTime = findViewById(R.id.tv_time)
        tvExerciseCounter = findViewById(R.id.tv_exercise_counter)
        circularTimer = findViewById(R.id.circular_timer)
        layoutTicks = findViewById(R.id.layout_ticks)
        layoutCounterContainer = findViewById(R.id.layout_counter_container)
        layoutPauseInfo = findViewById(R.id.layout_pause_info)
        clickInterceptor = findViewById(R.id.click_interceptor)

        val videoUri = intent.getParcelableExtra<Uri>("video_uri")
        videoItemId = intent.getStringExtra("video_item_id") ?: ""

        if (videoUri != null) {
            loadTimingsFromConfig()
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener { mp: MediaPlayer ->
                seekBar.max = mp.duration
                addDefaultTimings(mp.duration)
                drawTicks(mp.duration)
                videoView.start()
                handler.post(updateSeekRunnable)
            }
            videoView.setOnCompletionListener { finish() }
        }

        clickInterceptor.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                showControls(true)
            } else {
                videoView.start()
                showControls(false)
            }
        }

        btnStop.setOnClickListener { videoView.stopPlayback(); finish() }
        
        btnPrev.setOnClickListener {
            val pos = videoView.currentPosition
            val sorted = timings.sortedBy { it.time }
            val currentIdx = sorted.indexOfLast { it.time < pos - 500 }
            
            if (currentIdx != -1) {
                val currentTiming = sorted[currentIdx]
                if (pos - currentTiming.time > 10000) {
                    videoView.seekTo(currentTiming.time)
                } else if (currentIdx > 0) {
                    videoView.seekTo(sorted[currentIdx - 1].time)
                } else {
                    videoView.seekTo(0)
                }
            } else {
                videoView.seekTo(0)
            }
            if (!videoView.isPlaying) updateUIState()
        }

        btnNext.setOnClickListener {
            val pos = videoView.currentPosition
            val nextTiming = timings.filter { it.time > pos + 500 }.minByOrNull { it.time }
            if (nextTiming != null) {
                videoView.seekTo(nextTiming.time)
            }
            if (!videoView.isPlaying) updateUIState()
        }

        btnExerciseMenu.setOnClickListener { showExerciseDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    updateUIState()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateUIState() {
        val pos = videoView.currentPosition
        seekBar.progress = pos
        updateTimeDisplay()
        updateExerciseCounter(pos)
    }

    private fun addDefaultTimings(duration: Int) {
        var changed = false
        if (timings.none { it.time == 0 }) {
            timings.add(SettingsActivity.Timing(0, 0, 0, 0, 1))
            changed = true
        }
        if (duration > 0 && timings.none { it.time == duration }) {
            timings.add(SettingsActivity.Timing(duration, 0, 0, 0, 1))
            changed = true
        }
        if (changed) {
            timings.sortBy { it.time }
            saveTimingsToConfig()
        }
    }

    private fun drawTicks(duration: Int) {
        layoutTicks.post {
            layoutTicks.removeAllViews()
            val width = layoutTicks.width
            if (width <= 0 || duration <= 0) return@post
            
            timings.forEach { timing ->
                if (timing.time <= 0 || timing.time >= duration) return@forEach
                val tick = View(this).apply {
                    setBackgroundColor(Color.WHITE)
                    alpha = 0.7f
                }
                val params = FrameLayout.LayoutParams(2, FrameLayout.LayoutParams.MATCH_PARENT)
                params.leftMargin = (timing.time.toFloat() / duration * width).toInt()
                layoutTicks.addView(tick, params)
            }
        }
    }

    private fun updateExerciseCounter(pos: Int) {
        val sortedTimings = timings.sortedBy { it.time }
        val currentTiming = sortedTimings.filter { it.time <= pos }.maxByOrNull { it.time }
        
        if (currentTiming != null && currentTiming.max > 0) {
            tvExerciseCounter.text = currentTiming.curr.toString()
            layoutCounterContainer.visibility = View.VISIBLE
            
            val nextTiming = sortedTimings.filter { it.time > pos }.minByOrNull { it.time }
            val startTime = currentTiming.time
            val endTime = nextTiming?.time ?: videoView.duration
            
            if (endTime > startTime) {
                val total = endTime - startTime
                val elapsed = pos - startTime
                val progress = (elapsed.toFloat() / total * 100).toInt()
                circularTimer.progress = progress
            } else {
                circularTimer.progress = 100
            }
        } else {
            layoutCounterContainer.visibility = View.GONE
        }
    }

    private fun showExerciseDialog() {
        val pos = videoView.currentPosition
        val sorted = timings.sortedBy { it.time }
        
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
        val nextTiming = sorted.filter { it.time > pos }.minByOrNull { it.time }
        
        val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
        val targetTiming = exactTiming ?: currentTiming

        val startTimeStr = formatTime(targetTiming?.time ?: 0)
        val endTimeStr = formatTime(nextTiming?.time ?: videoView.duration)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val fieldsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        // Левая колонка
        val leftBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        leftBox.addView(TextView(this).apply { text = "Позиция:"; textSize = 12f })
        val tvMax = TextView(this).apply { 
            text = targetTiming?.max?.toString() ?: "0"
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.drawable.editbox_background_normal)
        }
        tvMax.setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            for (i in 0..999) pop.menu.add(i.toString())
            pop.setOnMenuItemClickListener { tvMax.text = it.title; true }
            pop.show()
        }
        leftBox.addView(tvMax)

        // Правая колонка
        val rightBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(20, 0, 0, 0)
        }
        rightBox.addView(TextView(this).apply { text = "Множитель:"; textSize = 12f })
        
        var selectedMultType = targetTiming?.multType ?: 0
        var selectedMultVal = targetTiming?.multVal ?: 1

        val tvMult = TextView(this).apply {
            text = when(selectedMultType) {
                1 -> "x$selectedMultVal"
                2 -> "№ файла $fileNumForDisplay"
                else -> "Пусто"
            }
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.drawable.editbox_background_normal)
        }
        
        tvMult.setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            pop.menu.add("Пусто")
            pop.menu.add("№ файла $fileNumForDisplay")
            val subMenu = pop.menu.addSubMenu("1-10")
            for (i in 1..10) subMenu.add("x$i")
            
            pop.setOnMenuItemClickListener { item ->
                val title = item.title.toString()
                when {
                    title == "Пусто" -> { selectedMultType = 0; selectedMultVal = 1; tvMult.text = "Пусто" }
                    title.startsWith("№ файла") -> { selectedMultType = 2; selectedMultVal = 1; tvMult.text = "№ файла $fileNumForDisplay" }
                    title.startsWith("x") -> {
                        selectedMultType = 1
                        selectedMultVal = if (title.length > 1) title.substring(1).toInt() else 1
                        tvMult.text = "x$selectedMultVal"
                    }
                }
                true
            }
            pop.show()
        }
        rightBox.addView(tvMult)

        fieldsLayout.addView(leftBox)
        fieldsLayout.addView(rightBox)
        dialogView.addView(fieldsLayout)

        val builder = AlertDialog.Builder(this)
            .setTitle("Установить $startTimeStr - $endTimeStr")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val max = tvMax.text.toString().toIntOrNull() ?: 0
                if (exactTiming != null) {
                    timings.remove(exactTiming)
                    timings.add(SettingsActivity.Timing(exactTiming.time, max, exactTiming.curr, selectedMultType, selectedMultVal))
                } else {
                    timings.add(SettingsActivity.Timing(pos, max, 0, selectedMultType, selectedMultVal))
                }
                timings.sortBy { it.time }
                saveTimingsToConfig()
                drawTicks(videoView.duration)
                updateUIState()
            }
            .setNegativeButton("Отмена", null)
        
        if (exactTiming != null && exactTiming.time != 0 && exactTiming.time != videoView.duration) {
            builder.setNeutralButton("Удалить") { _, _ ->
                timings.remove(exactTiming)
                saveTimingsToConfig()
                drawTicks(videoView.duration)
                updateUIState()
            }
        }

        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.RED)
    }

    private fun loadTimingsFromConfig() {
        try {
            val folderUriStr = getSharedPreferences("TexfitPrefs", Context.MODE_PRIVATE).getString("selectedFolderUri", null) ?: return
            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
            val configFile = folder.findFile("texfit.cfg") ?: return
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                val json = JSONObject(inputStream.bufferedReader().readText())
                val videoItems = json.optJSONArray("video_items") ?: return
                
                for (i in 0 until videoItems.length()) {
                    val item = videoItems.getJSONObject(i)
                    if (item.optString("id") == videoItemId) { // ИЩЕМ ПО UUID
                        videoFileName = item.optString("f_n")
                        fileNumForDisplay = item.optString("n_f", "000")
                        val tArr = item.optJSONArray("timings")
                        timings.clear()
                        if (tArr != null) {
                            for (j in 0 until tArr.length()) {
                                val tObj = tArr.getJSONObject(j)
                                timings.add(SettingsActivity.Timing(
                                    tObj.getInt("t"), tObj.getInt("m"), tObj.getInt("c"),
                                    tObj.optInt("mt", 0), tObj.optInt("mv", 1)
                                ))
                            }
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) { Log.e("VideoPlayer", "Load error", e) }
    }

    private fun saveTimingsToConfig() {
        try {
            val folderUriStr = getSharedPreferences("TexfitPrefs", Context.MODE_PRIVATE).getString("selectedFolderUri", null) ?: return
            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
            val configFile = folder.findFile("texfit.cfg") ?: return
            val json: JSONObject
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                json = JSONObject(inputStream.bufferedReader().readText())
            } ?: return
            val videoItems = json.optJSONArray("video_items") ?: return
            for (i in 0 until videoItems.length()) {
                val item = videoItems.getJSONObject(i)
                if (item.optString("id") == videoItemId) { // СОХРАНЯЕМ ПО UUID
                    val tArr = JSONArray()
                    timings.forEach { 
                        val tObj = JSONObject()
                        tObj.put("t", it.time); tObj.put("m", it.max); tObj.put("c", it.curr)
                        tObj.put("mt", it.multType); tObj.put("mv", it.multVal)
                        tArr.put(tObj)
                    }
                    item.put("timings", tArr)
                    break
                }
            }
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer -> writer.write(json.toString(4)) }
            }
        } catch (e: Exception) { Log.e("VideoPlayer", "Save error", e) }
    }

    private fun showControls(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        layoutPauseInfo.visibility = visibility
        btnStop.visibility = visibility
        btnExerciseMenu.visibility = visibility
    }

    private fun updateTimeDisplay() {
        val current = videoView.currentPosition
        val total = videoView.duration
        if (total > 0) {
            tvTime.text = "${formatTime(current)} / ${formatTime(total)}"
        }
    }

    private fun formatTime(millis: Int): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, secs)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekRunnable)
        videoView.stopPlayback()
    }
}

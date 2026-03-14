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
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    private var itemIndexInPlaylist: Int = -1
    private var timings = mutableListOf<SettingsActivity.Timing>()
    
    private var activeTiming: SettingsActivity.Timing? = null
    private var taskStartTime: Long = 0
    private var lastPausedAt: Long = 0
    private var isSeeking: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    
    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isSeeking) {
                    val pos = videoView.currentPosition
                    seekBar.progress = pos
                    updateTimeDisplay()
                    if (videoView.isPlaying) {
                        processTaskLogic(pos)
                    }
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 100)
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
        itemIndexInPlaylist = intent.getIntExtra("item_index", -1)
        val lastPos = intent.getIntExtra("last_pos", 0)

        if (videoUri != null) {
            loadTimingsFromConfig()
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener { mp: MediaPlayer ->
                seekBar.max = mp.duration
                addDefaultTimings(mp.duration)
                drawTicks(mp.duration)
                
                // Восстанавливаем позицию и сразу на паузу
                videoView.seekTo(lastPos)
                videoView.pause()
                lastPausedAt = SystemClock.elapsedRealtime()
                showControls(true)
                
                resetTaskTimer()
                updateUIState()
                handler.post(updateSeekRunnable)
            }
            videoView.setOnCompletionListener { 
                saveCurrentPositionToConfig(0)
                finish() 
            }
        }

        clickInterceptor.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                lastPausedAt = SystemClock.elapsedRealtime()
                showControls(true)
            } else {
                videoView.start()
                if (taskStartTime > 0) {
                    taskStartTime += (SystemClock.elapsedRealtime() - lastPausedAt)
                }
                showControls(false)
            }
        }

        btnStop.setOnClickListener { 
            saveCurrentPositionToConfig(videoView.currentPosition)
            videoView.stopPlayback()
            finish() 
        }
        
        btnPrev.setOnClickListener {
            val pos = videoView.currentPosition
            val sorted = timings.sortedBy { it.time }
            val currentIdx = sorted.indexOfLast { it.time < pos - 100 }
            if (currentIdx != -1) {
                val target = if (pos - sorted[currentIdx].time > 1000) sorted[currentIdx] else if (currentIdx > 0) sorted[currentIdx - 1] else sorted[0]
                videoView.seekTo(target.time)
            } else {
                videoView.seekTo(0)
            }
            resetTaskTimer()
            updateUIState()
        }

        btnNext.setOnClickListener {
            val pos = videoView.currentPosition
            val nextTiming = timings.filter { it.time > pos + 100 }.minByOrNull { it.time }
            if (nextTiming != null) {
                videoView.seekTo(nextTiming.time)
            }
            resetTaskTimer()
            updateUIState()
        }

        btnExerciseMenu.setOnClickListener { showExerciseDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    resetTaskTimer()
                    updateUIState()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isSeeking = false }
        })
    }

    override fun onBackPressed() {
        saveCurrentPositionToConfig(videoView.currentPosition)
        super.onBackPressed()
    }

    private fun resetTaskTimer() {
        taskStartTime = SystemClock.elapsedRealtime()
        activeTiming = null
    }

    private fun processTaskLogic(pos: Int) {
        val sorted = timings.sortedBy { it.time }
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time } ?: return
        val nextTiming = sorted.filter { it.time > pos }.minByOrNull { it.time }
        val nextTime = nextTiming?.time ?: videoView.duration

        if (activeTiming != currentTiming) {
            activeTiming = currentTiming
            taskStartTime = SystemClock.elapsedRealtime()
        }

        if (currentTiming.max > 0) {
            layoutCounterContainer.visibility = View.VISIBLE
            val elapsedMs = SystemClock.elapsedRealtime() - taskStartTime
            val remainingMs = currentTiming.curr - elapsedMs

            if (remainingMs <= 0) {
                if (nextTiming != null) {
                    isSeeking = true
                    videoView.seekTo(nextTime)
                    activeTiming = nextTiming
                    taskStartTime = SystemClock.elapsedRealtime()
                    handler.postDelayed({ isSeeking = false }, 400)
                } else {
                    saveCurrentPositionToConfig(0)
                    finish()
                }
            } else {
                tvExerciseCounter.text = formatTime(remainingMs.toInt())
                circularTimer.progress = (remainingMs.toFloat() / currentTiming.curr * 100).toInt()
                if (pos >= nextTime - 200 && !isSeeking) {
                    isSeeking = true
                    videoView.seekTo(currentTiming.time)
                    handler.postDelayed({ isSeeking = false }, 400)
                }
            }
        } else {
            layoutCounterContainer.visibility = View.GONE
        }
    }

    private fun updateUIState() {
        val pos = videoView.currentPosition
        seekBar.progress = pos
        updateTimeDisplay()
        processTaskLogic(pos)
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
                val tick = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0.7f }
                val params = FrameLayout.LayoutParams(2, FrameLayout.LayoutParams.MATCH_PARENT)
                params.leftMargin = (timing.time.toFloat() / duration * width).toInt()
                layoutTicks.addView(tick, params)
            }
        }
    }

    private fun showExerciseDialog() {
        val pos = videoView.currentPosition
        val sorted = timings.sortedBy { it.time }
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
        val nextTiming = sorted.filter { it.time > pos }.minByOrNull { it.time }
        val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
        val targetTiming = exactTiming ?: currentTiming

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        var currentMax = targetTiming?.max ?: 0L
        var currentStep = targetTiming?.step ?: 0L

        val btnMax = createTimeButton("ЛИМИТ (MAX):", currentMax) { newMs -> currentMax = newMs }
        dialogView.addView(btnMax)

        val btnStep = createTimeButton("ШАГ ПРИБАВКИ:", currentStep) { newMs -> currentStep = newMs }
        dialogView.addView(btnStep)

        dialogView.addView(TextView(this).apply { text = "Множитель:"; textSize = 12f; setPadding(0, 20, 0, 0) })
        var selectedMultType = targetTiming?.multType ?: 0
        var selectedMultVal = targetTiming?.multVal ?: 1
        val tvMult = TextView(this).apply {
            text = when(selectedMultType) {
                1 -> "x$selectedMultVal"
                2 -> "№ файла $fileNumForDisplay"
                else -> "Пусто"
            }
            textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal)
        }
        tvMult.setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            pop.menu.add("Пусто"); pop.menu.add("№ файла $fileNumForDisplay")
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
        dialogView.addView(tvMult)

        val btnClearAll = Button(this).apply {
            text = "Очистить все тайминги"
            setTextColor(Color.BLUE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 30 }
            setOnClickListener {
                timings.clear()
                addDefaultTimings(videoView.duration)
                saveTimingsToConfig()
                drawTicks(videoView.duration)
                resetTaskTimer()
                updateUIState()
                Toast.makeText(this@VideoPlayerActivity, "Тайминги сброшены", Toast.LENGTH_SHORT).show()
            }
        }
        dialogView.addView(btnClearAll)

        val builder = AlertDialog.Builder(this)
            .setTitle("Установить ${formatTime(targetTiming?.time ?: 0)} - ${formatTime(nextTiming?.time ?: videoView.duration)}")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                if (exactTiming != null) {
                    timings.remove(exactTiming)
                    timings.add(SettingsActivity.Timing(exactTiming.time, currentMax, exactTiming.curr, currentStep, selectedMultType, selectedMultVal))
                } else {
                    timings.add(SettingsActivity.Timing(pos, currentMax, 0L, currentStep, selectedMultType, selectedMultVal))
                }
                timings.sortBy { it.time }
                saveTimingsToConfig()
                drawTicks(videoView.duration); resetTaskTimer(); updateUIState()
            }
            .setNegativeButton("Отмена", null)
        
        if (exactTiming != null && exactTiming.time != 0 && exactTiming.time != videoView.duration) {
            builder.setNeutralButton("Удалить") { _, _ ->
                timings.remove(exactTiming); saveTimingsToConfig(); drawTicks(videoView.duration); resetTaskTimer(); updateUIState()
            }
        }

        val dialog = builder.create(); dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.RED)
    }

    private fun createTimeButton(label: String, initialMs: Long, onResult: (Long) -> Unit): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 0) }
        root.addView(TextView(this).apply { text = label; textSize = 12f })
        val tv = TextView(this).apply {
            text = formatTime(initialMs.toInt())
            textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal)
        }
        tv.setOnClickListener {
            val totalSec = initialMs / 1000
            val minPicker = NumberPicker(this).apply { minValue = 0; maxValue = 99; value = (totalSec / 60).toInt() }
            val secPicker = NumberPicker(this).apply { minValue = 0; maxValue = 59; value = (totalSec % 60).toInt() }
            val pickerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(20, 20, 20, 20)
                addView(minPicker); addView(TextView(this@VideoPlayerActivity).apply { text = ":"; textSize = 24f; setPadding(10, 0, 10, 0) }); addView(secPicker)
            }
            AlertDialog.Builder(this).setTitle(label).setView(pickerLayout)
                .setPositiveButton("OK") { _, _ ->
                    val newMs = (minPicker.value * 60 + secPicker.value) * 1000L
                    tv.text = formatTime(newMs.toInt())
                    onResult(newMs)
                }.setNegativeButton("Отмена", null).show()
        }
        root.addView(tv); return root
    }

    private fun saveCurrentPositionToConfig(pos: Int) {
        if (itemIndexInPlaylist == -1) return
        try {
            val folderUriStr = getSharedPreferences("TexfitPrefs", Context.MODE_PRIVATE).getString("selectedFolderUri", null) ?: return
            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
            val configFile = folder.findFile("texfit.cfg") ?: return
            val json: JSONObject
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                json = JSONObject(inputStream.bufferedReader().readText())
            } ?: return
            
            val titlesArray = json.optJSONArray("titles") ?: return
            val entry = titlesArray.optJSONArray(itemIndexInPlaylist) ?: return
            
            // Формат titles: [ID, статус, позиция_паузы]
            entry.put(2, pos)

            contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer -> writer.write(json.toString(4)) }
            }
        } catch (e: Exception) { Log.e("VideoPlayer", "Save pos error", e) }
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
                    if (item.optString("id") == videoItemId) {
                        videoFileName = item.optString("f_n")
                        fileNumForDisplay = item.optString("n_f", "000")
                        val tArr = item.optJSONArray("timings")
                        timings.clear()
                        if (tArr != null) {
                            for (j in 0 until tArr.length()) {
                                val tObj = tArr.getJSONObject(j)
                                timings.add(SettingsActivity.Timing(
                                    tObj.getInt("t"), tObj.optLong("m", 0), tObj.optLong("c", 0),
                                    tObj.optLong("s", 0), tObj.optInt("mt", 0), tObj.optInt("mv", 1)
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
                if (item.optString("id") == videoItemId) {
                    val tArr = JSONArray()
                    timings.forEach { 
                        val tObj = JSONObject()
                        tObj.put("t", it.time); tObj.put("m", it.max); tObj.put("c", it.curr)
                        tObj.put("s", it.step); tObj.put("mt", it.multType); tObj.put("mv", it.multVal)
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
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekRunnable)
        videoView.stopPlayback()
    }
}

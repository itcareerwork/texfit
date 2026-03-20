package com.prim.texfit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.Locale

class VideoPlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var seekBar: SeekBar
    private lateinit var btnStop: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnExerciseMenu: Button
    private lateinit var tvTime: TextView
    private lateinit var tvExerciseCounter: TextView
    private lateinit var tvCenterStopwatch: TextView
    private lateinit var circularTimer: CircularProgressIndicator
    private lateinit var layoutTicks: FrameLayout
    private lateinit var layoutCounterContainer: View
    private lateinit var layoutPauseInfo: View
    private lateinit var clickInterceptor: View

    private lateinit var layoutBottomStopwatch: View
    private lateinit var tvBottomStopwatchTime: TextView
    private lateinit var btnStopwatchToggle: Button

    private var videoItemId: String = ""
    private var videoFileName: String = ""
    private var fileNumForDisplay: String = "000"
    private var itemIndexInPlaylist: Int = -1
    private var timings = mutableListOf<SettingsActivity.Timing>()

    private var activeTiming: SettingsActivity.Timing? = null
    private var isSeeking: Boolean = false
    private var activeTimingTime: Int = -1
    private var activeSegmentEnd: Int = -1
    private var segmentPlayedMs: Long = 0L
    private var lastVideoPos: Int = 0
    private var pendingSkipZeroCurr: Boolean = false
    private var lastTickRealtime: Long = 0L
    private var pendingSeekClear: Boolean = false
    private var lastIssuedSeekTarget: Int = -1
    private var lastIssuedSeekRealtime: Long = 0L
    private var userSeekAnchorPos: Int? = null

    private var isExerciseMode: Boolean = true
    private var isStopwatchVisible: Boolean = false
    private var stopwatchBaseTime: Long = 0L
    private var stopwatchRunning: Boolean = false

    private fun beginSeek(targetMs: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastIssuedSeekTarget == targetMs && now - lastIssuedSeekRealtime < 350) return
        val current = player.currentPosition.toInt()
        if (kotlin.math.abs(current - targetMs) <= 25) return

        lastIssuedSeekTarget = targetMs
        lastIssuedSeekRealtime = now
        isSeeking = true
        pendingSeekClear = true
        player.seekTo(targetMs.toLong())
        lastVideoPos = targetMs
        lastTickRealtime = SystemClock.elapsedRealtime()
    }

    private fun clearActiveSegmentState() {
        activeTiming = null
        activeTimingTime = -1
        activeSegmentEnd = -1
        segmentPlayedMs = 0L
        pendingSkipZeroCurr = false
        userSeekAnchorPos = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isSeeking) {
                    val pos = player.currentPosition.toInt()
                    seekBar.progress = pos
                    updateTimeDisplay()
                    processTaskLogic(pos, player.isPlaying)
                    updateStopwatchDisplay()
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        playerView.useController = false
        seekBar = findViewById(R.id.seek_bar)
        btnStop = findViewById(R.id.btn_stop)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnExerciseMenu = findViewById(R.id.btn_exercise_menu)
        tvTime = findViewById(R.id.tv_time)
        tvExerciseCounter = findViewById(R.id.tv_exercise_counter)
        tvCenterStopwatch = findViewById(R.id.tv_center_stopwatch)
        circularTimer = findViewById(R.id.circular_timer)
        layoutTicks = findViewById(R.id.layout_ticks)
        layoutCounterContainer = findViewById(R.id.layout_counter_container)
        layoutPauseInfo = findViewById(R.id.layout_pause_info)
        clickInterceptor = findViewById(R.id.click_interceptor)

        layoutBottomStopwatch = findViewById(R.id.layout_bottom_stopwatch)
        tvBottomStopwatchTime = findViewById(R.id.tv_bottom_stopwatch_time)
        btnStopwatchToggle = findViewById(R.id.btn_stopwatch_toggle)

        val videoUri = intent.getParcelableExtra<Uri>("video_uri")
        videoItemId = intent.getStringExtra("video_item_id") ?: ""
        itemIndexInPlaylist = intent.getIntExtra("item_index", -1)
        val lastPos = intent.getIntExtra("last_pos", 0)

        if (videoUri != null) {
            loadTimingsFromConfig()
            setupStopwatch()

            player = ExoPlayer.Builder(this).build()
            playerView.player = player
            player.setSeekParameters(SeekParameters.EXACT)
            player.setMediaItem(MediaItem.fromUri(videoUri))
            player.addListener(object : Player.Listener {
                private var preparedOnce = false

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            val d = player.duration
                            if (!preparedOnce && d != C.TIME_UNSET) {
                                preparedOnce = true
                                seekBar.max = d.toInt()
                                addDefaultTimings(d.toInt())
                                drawTicks(d.toInt())

                                player.seekTo(lastPos.toLong())
                                player.pause()
                                showControls(true)

                                resetTaskTimer()
                                updateUIState()
                                handler.post(updateSeekRunnable)
                            }
                        }
                        Player.STATE_ENDED -> handlePlayerEnded()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        if (pendingSeekClear) {
                            pendingSeekClear = false
                            isSeeking = false
                        }
                        lastVideoPos = player.currentPosition.toInt()
                        lastTickRealtime = SystemClock.elapsedRealtime()
                    }
                }
            })
            player.prepare()
        }

        clickInterceptor.setOnClickListener {
            if (player.playWhenReady) {
                player.pause()
                showControls(true)
            } else {
                player.play()
                showControls(false)
            }
        }

        btnStop.setOnClickListener {
            saveCurrentPositionToConfig(player.currentPosition.toInt())
            player.stop()
            finish()
        }

        btnPrev.setOnClickListener {
            val pos = player.currentPosition.toInt()
            val sorted = timings.sortedBy { it.time }
            val currentIdx = sorted.indexOfLast { it.time < pos - 100 }
            if (currentIdx != -1) {
                val target = if (pos - sorted[currentIdx].time > 1000) sorted[currentIdx] else if (currentIdx > 0) sorted[currentIdx - 1] else sorted[0]
                beginSeek(clampSeekTarget(target.time + 10))
            } else {
                beginSeek(0)
            }
            resetTaskTimer()
            updateUIState()
        }

        btnNext.setOnClickListener {
            val pos = player.currentPosition.toInt()
            val nextTiming = timings.filter { it.time > pos + 100 }.minByOrNull { it.time }
            if (nextTiming != null) {
                beginSeek(clampSeekTarget(nextTiming.time + 10))
            } else {
                // Если мы на последнем сегменте, завершаем видео
                saveCurrentPositionToConfig(0)
                finish()
            }
            resetTaskTimer()
            updateUIState()
        }

        btnExerciseMenu.setOnClickListener { showExerciseDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    userSeekAnchorPos = progress
                    beginSeek(progress)
                    resetTaskTimer()
                    updateUIState()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isSeeking = false }
        })
    }

    private fun setupStopwatch() {
        layoutBottomStopwatch.visibility = if (isStopwatchVisible) View.VISIBLE else View.GONE
        btnStopwatchToggle.setOnClickListener {
            if (stopwatchRunning) {
                stopwatchRunning = false
                btnStopwatchToggle.text = "START"
            } else {
                stopwatchBaseTime = SystemClock.elapsedRealtime()
                stopwatchRunning = true
                btnStopwatchToggle.text = "STOP"
            }
        }
        btnStopwatchToggle.setOnLongClickListener {
            stopwatchBaseTime = SystemClock.elapsedRealtime()
            if (!stopwatchRunning) {
                tvBottomStopwatchTime.text = "00:00"
            }
            true
        }
    }

    override fun onBackPressed() {
        saveCurrentPositionToConfig(player.currentPosition.toInt())
        super.onBackPressed()
    }

    private fun resetTaskTimer() {
        activeTiming = null
        activeTimingTime = -1
        activeSegmentEnd = -1
        segmentPlayedMs = 0L
        lastVideoPos = player.currentPosition.toInt()
        pendingSkipZeroCurr = false
        lastTickRealtime = SystemClock.elapsedRealtime()
    }

    private fun clampSeekTarget(targetMs: Int): Int {
        val d = player.duration
        if (d == C.TIME_UNSET || d <= 0) return targetMs.coerceAtLeast(0)
        return targetMs.coerceIn(0, (d.toInt() - 1).coerceAtLeast(0))
    }

    private fun handlePlayerEnded() {
        val sorted = timings.sortedBy { it.time }
        val d = player.duration
        if (d != C.TIME_UNSET) {
            val pos = d.toInt()
            val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
            // Если последний сегмент требует повтора
            if (currentTiming != null && currentTiming.isEnabled && currentTiming.max > 0L) {
                val currMs = currentTiming.curr.coerceAtLeast(0L)
                if (segmentPlayedMs < currMs) {
                    val target = clampSeekTarget(currentTiming.time + 10)
                    beginSeek(target)
                    player.play()
                    return
                }
            }
        }
        clearActiveSegmentState()
        saveCurrentPositionToConfig(0)
        finish()
    }

    private fun updateStopwatchDisplay() {
        if (!isStopwatchVisible || !stopwatchRunning) return
        val elapsed = SystemClock.elapsedRealtime() - stopwatchBaseTime
        tvBottomStopwatchTime.text = formatTime(elapsed.toInt())
    }

    private fun processTaskLogic(pos: Int, isPlaying: Boolean) {
        val sorted = timings.sortedBy { it.time }
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time } ?: return

        // 1.1.1: Обычный режим сегмента
        if (!currentTiming.isEnabled) {
            layoutCounterContainer.visibility = View.GONE
            activeTiming = null
            activeTimingTime = -1
            activeSegmentEnd = -1
            segmentPlayedMs = 0L
            lastVideoPos = pos
            lastTickRealtime = SystemClock.elapsedRealtime()
            return
        }

        val nextTiming = sorted.filter { it.time > currentTiming.time }.minByOrNull { it.time }
        val segmentStart = currentTiming.time
        val totalDur = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()
        val segmentEnd = nextTiming?.time ?: totalDur

        // 1.1.2: Пропуск сегмента при MAX = 0
        if (currentTiming.max <= 0L) {
            layoutCounterContainer.visibility = View.GONE
            if (isPlaying && !isSeeking) {
                if (nextTiming != null) {
                    val target = clampSeekTarget(segmentEnd + 10)
                    if (pos < target - 20) {
                        clearActiveSegmentState()
                        beginSeek(target)
                        return
                    }
                } else {
                    // Последний сегмент - завершаем видео
                    clearActiveSegmentState()
                    saveCurrentPositionToConfig(0)
                    finish()
                    return
                }
            }
            activeTiming = null
            activeTimingTime = -1
            activeSegmentEnd = -1
            segmentPlayedMs = 0L
            lastVideoPos = pos
            lastTickRealtime = SystemClock.elapsedRealtime()
            return
        }

        // Логика активного упражнения
        if (activeTimingTime != currentTiming.time) {
            activeTiming = currentTiming
            activeTimingTime = currentTiming.time
            activeSegmentEnd = segmentEnd
            segmentPlayedMs = 0L
            lastVideoPos = pos
            pendingSkipZeroCurr = false
            lastTickRealtime = SystemClock.elapsedRealtime()
        }

        layoutCounterContainer.visibility = View.VISIBLE

        val currMs = currentTiming.curr.coerceAtLeast(0L)
        if (currMs == 0L) {
            // Пропуск если текущее значение упражнения = 0
            if (nextTiming != null && segmentEnd > segmentStart) {
                if (isPlaying && !isSeeking) {
                    val target = clampSeekTarget(segmentEnd + 10)
                    if (pos < target - 50) {
                        clearActiveSegmentState()
                        beginSeek(target)
                    }
                }
            } else if (nextTiming == null) {
                // Последний сегмент с curr=0
                if (isPlaying && !isSeeking) {
                    saveCurrentPositionToConfig(0)
                    finish()
                }
            }
            tvExerciseCounter.text = formatTime(0)
            circularTimer.progress = 0
            lastVideoPos = pos
            lastTickRealtime = SystemClock.elapsedRealtime()
            return
        }

        // Накопление времени воспроизведения
        val now = SystemClock.elapsedRealtime()
        if (isPlaying && !isSeeking) {
            val dt = now - lastTickRealtime
            if (dt > 0) segmentPlayedMs += dt
        }
        lastTickRealtime = now
        lastVideoPos = pos

        val remainingMs = (currMs - segmentPlayedMs).coerceAtLeast(0L)
        tvExerciseCounter.text = formatTime(remainingMs.toInt())
        circularTimer.progress = ((remainingMs.toFloat() / currMs.toFloat()) * 100f).toInt().coerceIn(0, 100)

        // Переход на следующий сегмент по завершении упражнения
        if (segmentPlayedMs >= currMs) {
            if (nextTiming != null) {
                val target = clampSeekTarget(segmentEnd + 10)
                if (!isSeeking) {
                    clearActiveSegmentState()
                    beginSeek(target)
                }
            } else {
                // Конец видео, упражнение на последнем отрезке выполнено.
                // Завершаем активность сразу, чтобы избежать "отскоков" и зацикливания таймера.
                if (!isSeeking) {
                    saveCurrentPositionToConfig(0)
                    finish()
                }
            }
            return
        }

        // Зацикливание внутри сегмента, если упражнение еще не закончено
        if (isPlaying && !isSeeking) {
            val endEpsilon = 150
            val timeLeftInSegment = (segmentEnd - pos).coerceAtLeast(0)
            val shouldLoop = timeLeftInSegment <= endEpsilon && remainingMs > (timeLeftInSegment + 50L)
            if (shouldLoop) {
                userSeekAnchorPos = null
                val target = clampSeekTarget(segmentStart + 10)
                beginSeek(target)
            }
        }
    }

    private fun updateUIState() {
        val pos = player.currentPosition.toInt()
        seekBar.progress = pos
        updateTimeDisplay()
        processTaskLogic(pos, player.isPlaying)
    }

    private fun addDefaultTimings(duration: Int) {
        var changed = false
        if (timings.none { it.time == 0 }) {
            timings.add(SettingsActivity.Timing(0, 0, 0, 0, 1, 1, true))
            changed = true
        }

        // Удаляем любые метки, которые находятся слишком близко к концу (менее 1 сек)
        val initialSize = timings.size
        timings.removeAll { it.time >= duration - 1000 && it.time > 0 }
        if (timings.size != initialSize) {
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

    private fun setViewAndChildrenEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewAndChildrenEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    private fun showExerciseDialog() {
        val pos = player.currentPosition.toInt()
        val sorted = timings.sortedBy { it.time }
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
        val nextTiming = sorted.filter { it.time > pos }.minByOrNull { it.time }
        val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
        val targetTiming = exactTiming ?: currentTiming
        val totalDur = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val greenColor = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        val colorStateList = ColorStateList.valueOf(greenColor)

        val switchExercise = Switch(this).apply {
            text = "Упражнение на этом отрезке"
            isChecked = targetTiming?.isEnabled ?: true
            setPadding(0, 0, 0, 20)
            thumbTintList = colorStateList
            trackTintList = colorStateList
        }
        dialogView.addView(switchExercise)

        val exerciseSettingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var currentMax = targetTiming?.max ?: 0L
        var currentStep = targetTiming?.step ?: 0L

        val btnMax = createTimeButton("ЛИМИТ (MAX):", currentMax) { newMs ->
            currentMax = newMs
            updateUIEnabledStateForDialog(exerciseSettingsContainer, switchExercise.isChecked, currentMax > 0)
        }
        btnMax.tag = "max_group"
        exerciseSettingsContainer.addView(btnMax)

        val btnStep = createTimeButton("ШАГ ПРИБАВКИ:", currentStep) { newMs -> currentStep = newMs }
        btnStep.tag = "step_group"
        exerciseSettingsContainer.addView(btnStep)

        exerciseSettingsContainer.addView(TextView(this).apply {
            text = "Множитель:"; textSize = 12f; setPadding(0, 20, 0, 0)
            tag = "mult_label"
        })

        var selectedMultType = targetTiming?.multType ?: 0
        var selectedMultVal = targetTiming?.multVal ?: 1
        val tvMult = TextView(this).apply {
            text = when(selectedMultType) {
                1 -> "x$selectedMultVal"
                2 -> "№ файла $fileNumForDisplay"
                else -> "Пусто"
            }
            textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal)
            tag = "mult_group"
        }
        tvMult.setOnClickListener { v ->
            if (currentMax <= 0L) {
                Toast.makeText(this, "Сначала задайте MAX", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
        exerciseSettingsContainer.addView(tvMult)
        dialogView.addView(exerciseSettingsContainer)

        switchExercise.setOnCheckedChangeListener { _, isChecked ->
            updateUIEnabledStateForDialog(exerciseSettingsContainer, isChecked, currentMax > 0)
        }
        updateUIEnabledStateForDialog(exerciseSettingsContainer, switchExercise.isChecked, currentMax > 0)

        val btnClearAll = Button(this).apply {
            text = "Очистить все тайминги"
            setTextColor(Color.BLUE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 30 }
            setOnClickListener {
                timings.clear()
                addDefaultTimings(if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt())
                saveTimingsToConfig()
                drawTicks(if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt())
                resetTaskTimer()
                updateUIState()
                Toast.makeText(this@VideoPlayerActivity, "Тайминги сброшены", Toast.LENGTH_SHORT).show()
            }
        }
        dialogView.addView(btnClearAll)

        val nextLabel = if (nextTiming != null) formatTime(nextTiming.time) else formatTime(totalDur)
        val builder = AlertDialog.Builder(this)
            .setTitle("Установить ${formatTime(targetTiming?.time ?: 0)} - $nextLabel")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                if (exactTiming != null) {
                    timings.remove(exactTiming)
                    timings.add(SettingsActivity.Timing(exactTiming.time, currentMax, exactTiming.curr, currentStep, selectedMultType, selectedMultVal, switchExercise.isChecked))
                } else {
                    timings.add(SettingsActivity.Timing(pos, currentMax, 0L, currentStep, selectedMultType, selectedMultVal, switchExercise.isChecked))
                }
                timings.sortBy { it.time }
                saveTimingsToConfig()
                drawTicks(if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()); resetTaskTimer(); updateUIState()
            }
            .setNegativeButton("Отмена", null)

        if (exactTiming != null && exactTiming.time != 0) {
            builder.setNeutralButton("Удалить") { _, _ ->
                timings.remove(exactTiming); saveTimingsToConfig(); drawTicks(totalDur); resetTaskTimer(); updateUIState()
            }
        }

        val dialog = builder.create(); dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(greenColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(greenColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.RED)
    }

    private fun updateUIEnabledStateForDialog(container: ViewGroup, swOn: Boolean, maxOn: Boolean) {
        val btnMax = container.findViewWithTag<View>("max_group")
        val btnStep = container.findViewWithTag<View>("step_group")
        val tvMult = container.findViewWithTag<View>("mult_group")
        val multLabel = container.findViewWithTag<View>("mult_label")

        setViewAndChildrenEnabled(btnMax, swOn)
        btnMax.alpha = if (swOn) 1f else 0.35f

        val stepMultEnabled = swOn && maxOn
        setViewAndChildrenEnabled(btnStep, stepMultEnabled)
        btnStep.alpha = if (stepMultEnabled) 1f else 0.35f

        setViewAndChildrenEnabled(tvMult, stepMultEnabled)
        tvMult.alpha = if (stepMultEnabled) 1f else 0.35f
        multLabel.alpha = if (stepMultEnabled) 1f else 0.35f
    }

    private fun showMmSsInput(initialMs: Long, title: String, onResult: (Long) -> Unit) {
        val totalSec = initialMs / 1000
        val m = (totalSec / 60).toInt()
        val s = (totalSec % 60).toInt()

        val etMin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "Мин"
            setText(m.toString()); setGravity(Gravity.CENTER)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val etSec = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "Сек"
            setText(String.format("%02d", s)); setGravity(Gravity.CENTER)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val container = LinearLayout(this).apply {
            setPadding(60, 20, 60, 0); orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(etMin); addView(TextView(this@VideoPlayerActivity).apply { text = ":"; setRawInputType(InputType.TYPE_CLASS_NUMBER); textSize = 24f; setPadding(10, 0, 10, 0) })
            addView(etSec)
        }

        val d = AlertDialog.Builder(this)
            .setTitle(title).setView(container)
            .setPositiveButton("OK") { _, _ ->
                val mins = etMin.text.toString().toIntOrNull() ?: 0
                val secs = etSec.text.toString().toIntOrNull() ?: 0
                onResult((mins * 60 + secs.coerceIn(0, 59)) * 1000L)
            }
            .setNegativeButton("Отмена", null).create()

        d.show()
        val greenColor = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(greenColor)
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(greenColor)

        etMin.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etMin, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun createTimeButton(label: String, initialMs: Long, onResult: (Long) -> Unit): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 0) }
        root.addView(TextView(this).apply { text = label; textSize = 12f })
        val tv = TextView(this).apply {
            text = formatTime(initialMs.toInt())
            textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal)
        }
        tv.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            showMmSsInput(initialMs, label) { newMs ->
                tv.text = formatTime(newMs.toInt())
                onResult(newMs)
            }
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

                if (json.has("stopwatch_enabled")) {
                    isStopwatchVisible = json.getBoolean("stopwatch_enabled")
                }

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
                                    tObj.optLong("s", 0), tObj.optInt("mt", 0), tObj.optInt("mv", 1),
                                    tObj.optBoolean("en", true)
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
                        tObj.put("en", it.isEnabled)
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
        val current = player.currentPosition.toInt()
        val total = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()
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
        try {
            playerView.player = null
            player.release()
        } catch (_: Exception) {}
    }
}

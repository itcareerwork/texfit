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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
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
    private lateinit var tvTime: TextView
    private lateinit var tvExerciseCounter: TextView
    private lateinit var circularTimer: CircularProgressIndicator
    private lateinit var layoutTicks: FrameLayout
    private lateinit var layoutCounterContainer: View
    private lateinit var layoutStickContainer: FrameLayout
    private lateinit var layoutPauseInfo: View
    private lateinit var clickInterceptor: View

    private lateinit var layoutBottomStopwatch: View
    private lateinit var tvBottomStopwatchTime: TextView
    private lateinit var btnStopwatchToggle: Button

    // Элементы управления упражнением на экране
    private lateinit var layoutExerciseControls: View
    private lateinit var tvControlSegmentLabel: TextView
    private lateinit var swExerciseEnabled: SwitchCompat
    private lateinit var layoutSettingsFields: View
    private lateinit var tvControlMax: TextView
    private lateinit var tvControlStep: TextView
    private lateinit var tvControlMult: TextView
    private lateinit var btnControlSave: Button
    private lateinit var btnControlDelete: Button
    private lateinit var btnControlClearAll: Button

    private var videoItemId: String = ""
    private var videoFileName: String = ""
    private var fileNumForDisplay: String = "000"
    private var itemIndexInPlaylist: Int = -1
    private var isFromSettings: Boolean = false
    private var timings = mutableListOf<SettingsActivity.Timing>()

    // Промежуточные переменные для хранения изменений до сохранения
    private var pendingEnabled: Boolean = false
    private var pendingMax: Long = 0L
    private var pendingStep: Long = 0L
    private var pendingMultType: Int = 0
    private var pendingMultVal: Int = 1

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
        tvTime = findViewById(R.id.tv_time)
        tvExerciseCounter = findViewById(R.id.tv_exercise_counter)
        circularTimer = findViewById(R.id.circular_timer)
        layoutTicks = findViewById(R.id.layout_ticks)
        layoutCounterContainer = findViewById(R.id.layout_counter_container)
        layoutStickContainer = findViewById(R.id.layout_stick_container)
        layoutPauseInfo = findViewById(R.id.layout_pause_info)
        clickInterceptor = findViewById(R.id.click_interceptor)

        layoutBottomStopwatch = findViewById(R.id.layout_bottom_stopwatch)
        tvBottomStopwatchTime = findViewById(R.id.tv_bottom_stopwatch_time)
        btnStopwatchToggle = findViewById(R.id.btn_stopwatch_toggle)

        // Инициализация элементов управления
        layoutExerciseControls = findViewById(R.id.layout_exercise_controls)
        tvControlSegmentLabel = findViewById(R.id.tv_control_segment_label)
        swExerciseEnabled = findViewById(R.id.sw_exercise_enabled)
        layoutSettingsFields = findViewById(R.id.layout_settings_fields)
        tvControlMax = findViewById(R.id.tv_control_max)
        tvControlStep = findViewById(R.id.tv_control_step)
        tvControlMult = findViewById(R.id.tv_control_mult)
        btnControlSave = findViewById(R.id.btn_control_save)
        btnControlDelete = findViewById(R.id.btn_control_delete)
        btnControlClearAll = findViewById(R.id.btn_control_clear_all)

        // Возвращаем цвета переключателя
        val greenColor = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        val grayColor = Color.GRAY
        val colorStateList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(greenColor, grayColor)
        )
        swExerciseEnabled.thumbTintList = colorStateList
        swExerciseEnabled.trackTintList = colorStateList

        val videoUri = intent.getParcelableExtra<Uri>("video_uri")
        videoItemId = intent.getStringExtra("video_item_id") ?: ""
        itemIndexInPlaylist = intent.getIntExtra("item_index", -1)
        isFromSettings = intent.getBooleanExtra("is_from_settings", false)
        isStopwatchVisible = isFromSettings
        val lastPos = intent.getIntExtra("last_pos", 0)

        if (videoUri != null) {
            loadTimingsFromConfig()
            setupStopwatch()
            setupExerciseControls()

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
            updateExerciseControlsUI()
        }

        btnNext.setOnClickListener {
            val pos = player.currentPosition.toInt()
            val nextTiming = timings.filter { it.time > pos + 100 }.minByOrNull { it.time }
            if (nextTiming != null) {
                beginSeek(clampSeekTarget(nextTiming.time + 10))
            } else {
                saveCurrentPositionToConfig(0)
                finish()
            }
            resetTaskTimer()
            updateUIState()
            updateExerciseControlsUI()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    userSeekAnchorPos = progress
                    beginSeek(progress)
                    resetTaskTimer()
                    updateUIState()
                    updateExerciseControlsUI()
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

    private fun setupExerciseControls() {
        if (!isFromSettings) return

        swExerciseEnabled.setOnCheckedChangeListener { _, isChecked ->
            pendingEnabled = isChecked
            updateExerciseControlsVisibility(isChecked, pendingMax > 0)
        }

        tvControlMax.setOnClickListener {
            showMmSsInput(pendingMax, "ЛИМИТ (MAX):") { newMs ->
                pendingMax = newMs
                tvControlMax.text = formatTime(newMs.toInt())
                updateExerciseControlsVisibility(pendingEnabled, newMs > 0)
            }
        }

        tvControlStep.setOnClickListener {
            showMmSsInput(pendingStep, "ШАГ ПРИБАВКИ:") { newMs ->
                pendingStep = newMs
                tvControlStep.text = formatTime(newMs.toInt())
            }
        }

        tvControlMult.setOnClickListener { v ->
            if (pendingMax <= 0L) {
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
                    title == "Пусто" -> { pendingMultType = 0; pendingMultVal = 1; tvControlMult.text = "Пусто" }
                    title.startsWith("№ файла") -> { pendingMultType = 2; pendingMultVal = 1; tvControlMult.text = "№ файла $fileNumForDisplay" }
                    title.startsWith("x") -> {
                        pendingMultType = 1
                        pendingMultVal = if (title.length > 1) title.substring(1).toInt() else 1
                        tvControlMult.text = "x$pendingMultVal"
                    }
                }
                true
            }
            pop.show()
        }

        btnControlSave.setOnClickListener {
            val pos = player.currentPosition.toInt()
            val target = findOrAddTiming(pos)
            target.isEnabled = pendingEnabled
            target.max = pendingMax
            target.step = pendingStep
            target.multType = pendingMultType
            target.multVal = pendingMultVal
            
            saveTimingsToConfig()
            drawTicks(player.duration.toInt())
            resetTaskTimer()
            updateUIState()
            updateExerciseControlsUI()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }

        btnControlDelete.setOnClickListener {
            val pos = player.currentPosition.toInt()
            val sorted = timings.sortedBy { it.time }
            val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
            if (exactTiming != null && exactTiming.time != 0) {
                timings.remove(exactTiming)
                saveTimingsToConfig()
                drawTicks(player.duration.toInt())
                resetTaskTimer()
                updateUIState()
                updateExerciseControlsUI()
            }
        }

        btnControlClearAll.setOnClickListener {
            timings.clear()
            addDefaultTimings(player.duration.toInt())
            saveTimingsToConfig()
            drawTicks(player.duration.toInt())
            resetTaskTimer()
            updateUIState()
            updateExerciseControlsUI()
            Toast.makeText(this, "Тайминги сброшены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findOrAddTiming(pos: Int): SettingsActivity.Timing {
        val sorted = timings.sortedBy { it.time }
        val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
        if (exactTiming != null) return exactTiming
        
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
        // При создании новой метки копируем настройки из предыдущей, но обнуляем прогресс (curr)
        val newTiming = SettingsActivity.Timing(
            pos, 
            currentTiming?.max ?: 0L, 
            0L, 
            currentTiming?.step ?: 0L, 
            currentTiming?.multType ?: 0, 
            currentTiming?.multVal ?: 1, 
            currentTiming?.isEnabled ?: false
        )
        timings.add(newTiming)
        timings.sortBy { it.time }
        return newTiming
    }

    private fun updateExerciseControlsUI() {
        if (!isFromSettings || layoutExerciseControls.visibility != View.VISIBLE) return

        val pos = player.currentPosition.toInt()
        val sorted = timings.sortedBy { it.time }
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time } ?: return
        val nextTiming = sorted.filter { it.time > pos }.minByOrNull { it.time }
        val totalDur = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()

        val nextLabel = if (nextTiming != null) formatTime(nextTiming.time) else formatTime(totalDur)
        tvControlSegmentLabel.text = "${formatTime(currentTiming.time)} - $nextLabel"
        
        // Инициализируем промежуточные переменные из текущего тайминга
        pendingEnabled = currentTiming.isEnabled
        pendingMax = currentTiming.max
        pendingStep = currentTiming.step
        pendingMultType = currentTiming.multType
        pendingMultVal = currentTiming.multVal

        swExerciseEnabled.isChecked = pendingEnabled
        tvControlMax.text = formatTime(pendingMax.toInt())
        tvControlStep.text = formatTime(pendingStep.toInt())
        tvControlMult.text = when(pendingMultType) {
            1 -> "x$pendingMultVal"
            2 -> "№ файла $fileNumForDisplay"
            else -> "Пусто"
        }
        
        updateExerciseControlsVisibility(pendingEnabled, pendingMax > 0)
        btnControlDelete.visibility = if (currentTiming.time == 0) View.GONE else View.VISIBLE
    }

    private fun updateExerciseControlsVisibility(swOn: Boolean, maxOn: Boolean) {
        tvControlMax.isEnabled = swOn
        tvControlMax.alpha = if (swOn) 1f else 0.4f
        
        val stepMultEnabled = swOn && maxOn
        tvControlStep.isEnabled = stepMultEnabled
        tvControlStep.alpha = if (stepMultEnabled) 1f else 0.4f
        tvControlMult.isEnabled = stepMultEnabled
        tvControlMult.alpha = if (stepMultEnabled) 1f else 0.4f
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
            if (nextTiming != null && segmentEnd > segmentStart) {
                if (isPlaying && !isSeeking) {
                    val target = clampSeekTarget(segmentEnd + 10)
                    if (pos < target - 50) {
                        clearActiveSegmentState()
                        beginSeek(target)
                    }
                }
            } else if (nextTiming == null) {
                if (isPlaying && !isSeeking) {
                    saveCurrentPositionToConfig(0)
                    finish()
                }
            }
            tvExerciseCounter.text = formatTime(0)
            circularTimer.progress = 0
            layoutStickContainer.visibility = View.GONE
            lastVideoPos = pos
            lastTickRealtime = SystemClock.elapsedRealtime()
            return
        }

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

        val stepMs = currentTiming.step.coerceAtLeast(0L)
        if (stepMs > 0) {
            val rotationAngle = -((segmentPlayedMs % stepMs).toFloat() / stepMs.toFloat()) * 360f
            layoutStickContainer.rotation = rotationAngle
            layoutStickContainer.visibility = View.VISIBLE
        } else {
            layoutStickContainer.visibility = View.GONE
        }

        if (segmentPlayedMs >= currMs) {
            if (nextTiming != null) {
                val target = clampSeekTarget(segmentEnd + 10)
                if (!isSeeking) {
                    clearActiveSegmentState()
                    beginSeek(target)
                }
            } else {
                if (!isSeeking) {
                    saveCurrentPositionToConfig(0)
                    finish()
                }
            }
            return
        }

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
            timings.add(SettingsActivity.Timing(0, 0, 0, 0, 1, 1, false))
            changed = true
        }
        val initialSize = timings.size
        timings.removeAll { it.time >= duration - 1000 && it.time > 0 }
        if (timings.size != initialSize) changed = true

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
            addView(etMin); addView(TextView(this@VideoPlayerActivity).apply { text = ":"; textSize = 24f; setPadding(10, 0, 10, 0) })
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

    private fun saveCurrentPositionToConfig(pos: Int) {
        if (itemIndexInPlaylist == -1 || isFromSettings) return
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
        
        if (isFromSettings) {
            layoutExerciseControls.visibility = visibility
            if (show) updateExerciseControlsUI()
        }
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

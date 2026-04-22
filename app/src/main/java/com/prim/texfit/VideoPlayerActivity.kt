package com.prim.texfit

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.Locale

@OptIn(UnstableApi::class)
class VideoPlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var seekBar: SeekBar
    private lateinit var btnStop: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
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
    private lateinit var tvStopwatchStatusLabel: TextView

    // Элементы управления упражнением на экране
    private lateinit var layoutExerciseControls: View
    private lateinit var tvControlSegmentLabel: TextView
    private lateinit var swExerciseEnabled: SwitchCompat
    private lateinit var layoutSettingsFields: View
    
    private lateinit var tvControlMaxMin: TextView
    private lateinit var tvControlMaxSec: TextView
    private lateinit var tvControlStepMin: TextView
    private lateinit var tvControlStepSec: TextView
    
    private lateinit var tvControlMult: TextView
    private lateinit var btnControlSave: Button
    private lateinit var btnControlDelete: Button
    private lateinit var btnControlGeneralSettings: Button

    // Кнопки тонкой настройки
    private lateinit var btnFineBack: ImageButton
    private lateinit var btnFineForward: ImageButton

    private var videoItemId: String = ""
    private var videoFileName: String = ""
    private var fileNumForDisplay: String = "000"
    private var itemIndexInPlaylist: Int = -1
    private var isFromSettings: Boolean = false
    private var timings = mutableListOf<SettingsActivity.Timing>()
    private var currStepConfig: Int = 1

    // Промежуточные переменные
    private var pendingEnabled: Boolean = false
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

    private var completionPlayer: MediaPlayer? = null
    private var isCompletionSoundPlayed = false
    private var initialSegmentPlayed: Long = 0L
    private var isInitialSegmentRestored: Boolean = false

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val KEY_PLAYLIST = "playlist_data"
    }

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_video_player)

        // Инициализация звука из файла dzyn.mp3
        try {
            completionPlayer = MediaPlayer.create(this, R.raw.dzyn)
        } catch (e: Exception) { Log.e("VideoPlayer", "Failed to init MediaPlayer", e) }

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
        tvStopwatchStatusLabel = findViewById(R.id.tv_stopwatch_status_label)

        layoutExerciseControls = findViewById(R.id.layout_exercise_controls)
        tvControlSegmentLabel = findViewById(R.id.tv_control_segment_label)
        swExerciseEnabled = findViewById(R.id.sw_exercise_enabled)
        layoutSettingsFields = findViewById(R.id.layout_settings_fields)

        tvControlMaxMin = findViewById(R.id.tv_control_max_min); tvControlMaxSec = findViewById(R.id.tv_control_max_sec)
        tvControlStepMin = findViewById(R.id.tv_control_step_min); tvControlStepSec = findViewById(R.id.tv_control_step_sec)

        tvControlMult = findViewById(R.id.tv_control_mult)
        btnControlSave = findViewById(R.id.btn_control_save)
        btnControlDelete = findViewById(R.id.btn_control_delete)
        btnControlGeneralSettings = findViewById(R.id.btn_control_general_settings)

        btnFineBack = findViewById(R.id.btn_fine_back); btnFineForward = findViewById(R.id.btn_fine_forward)

        val greenColor = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        val grayColor = Color.GRAY
        val colorStateList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(greenColor, grayColor))
        swExerciseEnabled.thumbTintList = colorStateList; swExerciseEnabled.trackTintList = colorStateList

        val videoUri = intent.getParcelableExtra<Uri>("video_uri")
        videoItemId = intent.getStringExtra("video_item_id") ?: ""
        itemIndexInPlaylist = intent.getIntExtra("item_index", -1)
        isFromSettings = intent.getBooleanExtra("is_from_settings", false)
        isStopwatchVisible = isFromSettings
        val lastPos = intent.getIntExtra("last_pos", 0)
        initialSegmentPlayed = intent.getLongExtra("segment_played", 0L)
        isInitialSegmentRestored = false

        if (videoUri != null) {
            loadTimingsFromConfig()
            setupStopwatch()
            setupExerciseControls()
            setupFineTuningControls()

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
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        if (pendingSeekClear) { pendingSeekClear = false; isSeeking = false }
                        lastVideoPos = player.currentPosition.toInt()
                        lastTickRealtime = SystemClock.elapsedRealtime()
                    }
                }
            })
            player.prepare()
        }

        clickInterceptor.setOnClickListener { if (player.playWhenReady) { player.pause(); showControls(true) } else { player.play(); showControls(false) } }
        btnStop.setOnClickListener { saveCurrentPositionToPrefs(player.currentPosition.toInt()); player.stop(); finish() }
        btnPrev.setOnClickListener {
            val pos = player.currentPosition.toInt(); val sorted = timings.sortedBy { it.time }; val currentIdx = sorted.indexOfLast { it.time < pos - 100 }
            if (currentIdx != -1) { val target = if (pos - sorted[currentIdx].time > 1000) sorted[currentIdx] else if (currentIdx > 0) sorted[currentIdx - 1] else sorted[0]; beginSeek(clampSeekTarget(target.time + 10)) }
            else beginSeek(0)
            resetTaskTimer(); updateUIState(); updateExerciseControlsUI()
        }
        btnNext.setOnClickListener {
            val pos = player.currentPosition.toInt(); val nextTiming = timings.filter { it.time > pos + 100 }.minByOrNull { it.time }
            if (nextTiming != null) beginSeek(clampSeekTarget(nextTiming.time + 10))
            else { saveCurrentPositionToPrefs(0); finish() }
            resetTaskTimer(); updateUIState(); updateExerciseControlsUI()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { userSeekAnchorPos = progress; beginSeek(progress); resetTaskTimer(); updateUIState(); updateExerciseControlsUI() } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isSeeking = false }
        })
    }

    private fun setupFineTuningControls() {
        if (!isFromSettings) return
        btnFineBack.setOnClickListener { val current = if (isSeeking) lastVideoPos else player.currentPosition.toInt(); beginSeek((current - 100).coerceAtLeast(0)); updateUIState(); updateExerciseControlsUI() }
        btnFineForward.setOnClickListener { val current = if (isSeeking) lastVideoPos else player.currentPosition.toInt(); val total = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt(); beginSeek((current + 100).coerceAtMost(total)); updateUIState(); updateExerciseControlsUI() }
    }

    private fun toggleStopwatch() {
        if (stopwatchRunning) { stopwatchRunning = false; tvStopwatchStatusLabel.text = "СЕКУНДОМЕР"; tvStopwatchStatusLabel.alpha = 0.7f }
        else { stopwatchBaseTime = SystemClock.elapsedRealtime(); stopwatchRunning = true; tvStopwatchStatusLabel.text = "СТОП"; tvStopwatchStatusLabel.alpha = 1.0f }
    }

    private fun resetStopwatch() { stopwatchBaseTime = SystemClock.elapsedRealtime(); if (!stopwatchRunning) tvBottomStopwatchTime.text = "00:00" }

    private fun setupStopwatch() {
        layoutBottomStopwatch.visibility = if (isStopwatchVisible) View.VISIBLE else View.GONE
        layoutBottomStopwatch.setOnClickListener { toggleStopwatch() }
        layoutBottomStopwatch.setOnLongClickListener { resetStopwatch(); true }
    }

    private fun setupExerciseControls() {
        if (!isFromSettings) return
        swExerciseEnabled.setOnCheckedChangeListener { _, isChecked -> pendingEnabled = isChecked; updateExerciseControlsVisibility(isChecked, getMsFromUI(isMax = true) > 0) }
        tvControlMaxMin.setOnClickListener { showNumericKeypadPopup(tvControlMaxMin) }; tvControlMaxSec.setOnClickListener { showNumericKeypadPopup(tvControlMaxSec) }
        tvControlStepMin.setOnClickListener { showNumericKeypadPopup(tvControlStepMin) }; tvControlStepSec.setOnClickListener { showNumericKeypadPopup(tvControlStepSec) }
        tvControlMult.setOnClickListener { v ->
            if (getMsFromUI(isMax = true) <= 0L) { Toast.makeText(this, "Сначала задайте MAX", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val pop = PopupMenu(this, v); pop.menu.add("--"); pop.menu.add("№ файла $fileNumForDisplay")
            val subMenu = pop.menu.addSubMenu("1-10")
            for (i in 1..10) subMenu.add("x$i")
            pop.setOnMenuItemClickListener { item ->
                val title = item.title.toString()
                when { title == "--" -> { pendingMultType = 0; pendingMultVal = 1; tvControlMult.text = "--" }; title.startsWith("№ файла") -> { pendingMultType = 2; pendingMultVal = 1; tvControlMult.text = "№ файла $fileNumForDisplay" }; title.startsWith("x") -> { pendingMultType = 1; pendingMultVal = if (title.length > 1) title.substring(1).toInt() else 1; tvControlMult.text = "x$pendingMultVal" } }
                true
            }
            pop.show()
        }
        btnControlSave.setOnClickListener {
            val pos = player.currentPosition.toInt(); val target = findOrAddTiming(pos)
            target.isEnabled = pendingEnabled; target.max = getMsFromUI(isMax = true); target.step = getMsFromUI(isMax = false); target.multType = pendingMultType; target.multVal = pendingMultVal
            if (currStepConfig == 1) target.curr = if (target.step > 0) -target.step else 0L else target.curr = -1L
            saveTimingsToConfig() ; drawTicks(player.duration.toInt()); resetTaskTimer(); updateUIState(); updateExerciseControlsUI(); Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }
        btnControlDelete.setOnClickListener {
            val pos = player.currentPosition.toInt(); val sorted = timings.sortedBy { it.time }; val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
            if (exactTiming != null && exactTiming.time != 0) { timings.remove(exactTiming); saveTimingsToConfig(); drawTicks(player.duration.toInt()); resetTaskTimer(); updateUIState(); updateExerciseControlsUI() }
        }
        btnControlGeneralSettings.setOnClickListener { showGeneralSettingsPopup(it) }
    }

    private fun getMsFromUI(isMax: Boolean = true): Long {
        val mins = (if (isMax) tvControlMaxMin.text else tvControlStepMin.text).toString().toIntOrNull() ?: 0
        val secs = (if (isMax) tvControlMaxSec.text else tvControlStepSec.text).toString().toIntOrNull() ?: 0
        return (mins * 60 + secs) * 1000L
    }

    private var popup: PopupWindow? = null
    private fun showNumericKeypadPopup(target: TextView) {
        val isMinField = target == tvControlMaxMin || target == tvControlStepMin
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12); setBackgroundColor(Color.parseColor("#E0000000")) }
        val display = TextView(this).apply { text = ""; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, 8) }; dialogView.addView(display)
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 4; alignmentMode = GridLayout.ALIGN_BOUNDS }
        var currentInput = ""
        val onDigitClick = { digit: String -> if (currentInput.length < 2) { currentInput += digit; display.text = currentInput; if (currentInput.length == 2) { var value = currentInput.toInt(); if (isMinField) { if (value > 90) value = 90 } else { if (value > 59) value = 59 }; target.text = String.format(Locale.US, "%02d", value); updateExerciseControlsVisibility(pendingEnabled, getMsFromUI(isMax = true) > 0); handler.postDelayed({ popup?.dismiss() }, 200) } } }
        val btnSize = (48 * resources.displayMetrics.density).toInt()
        fun createKeypadBtn(label: String, onClick: (String) -> Unit): View = Button(this).apply { text = label; textSize = 18f; setTextColor(Color.WHITE); background = ContextCompat.getDrawable(this@VideoPlayerActivity, R.drawable.btn_round_bg); backgroundTintList = ColorStateList.valueOf(Color.parseColor("#80FFFFFF")); layoutParams = GridLayout.LayoutParams().apply { width = btnSize; height = btnSize; setMargins(4, 4, 4, 4) }; setPadding(0, 0, 0, 0); setOnClickListener { onClick(label) } }
        for (i in 1..9) grid.addView(createKeypadBtn(i.toString(), onDigitClick))
        grid.addView(createKeypadBtn("C") { currentInput = ""; display.text = "" }); grid.addView(createKeypadBtn("0", onDigitClick)); grid.addView(createKeypadBtn("OK") { if (currentInput.isNotEmpty()) { var value = currentInput.toInt(); if (isMinField) { if (value > 90) value = 90 } else { if (value > 59) value = 59 }; target.text = String.format(Locale.US, "%02d", value); updateExerciseControlsVisibility(pendingEnabled, getMsFromUI(isMax = true) > 0) }; popup?.dismiss() })
        dialogView.addView(grid); popup = PopupWindow(dialogView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true); popup?.showAsDropDown(target)
    }

    private fun showGeneralSettingsPopup(anchor: View) {
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(Color.parseColor("#E0000000")) }
        fun createPopupBtn(label: String, color: Int, onClick: () -> Unit): Button = Button(this).apply { text = label; textSize = 14f; setTextColor(Color.WHITE); background = ContextCompat.getDrawable(this@VideoPlayerActivity, R.drawable.btn_round_bg); backgroundTintList = ColorStateList.valueOf(color); setOnClickListener { onClick(); popup?.dismiss() }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (42 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() } }
        dialogView.addView(createPopupBtn("Сбросить все", Color.parseColor("#F57C00")) { timings.forEach { t -> if (currStepConfig == 1) t.curr = if (t.step > 0) -t.step else 0L else t.curr = -1L }; saveTimingsToConfig() ; resetTaskTimer(); updateUIState(); updateExerciseControlsUI(); Toast.makeText(this, "Все тайминги сброшены", Toast.LENGTH_SHORT).show() })
        dialogView.addView(createPopupBtn("Удалить все", Color.parseColor("#1565C0")) { timings.clear(); addDefaultTimings(player.duration.toInt()); saveTimingsToConfig() ; drawTicks(player.duration.toInt()); resetTaskTimer(); updateUIState(); updateExerciseControlsUI(); Toast.makeText(this, "Тайминги удалены", Toast.LENGTH_SHORT).show() })
        popup = PopupWindow(dialogView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true); popup?.showAsDropDown(anchor)
    }

    private fun setFieldsFromMs(ms: Long, tvMin: TextView, tvSec: TextView) { val totalSec = ms / 1000; tvMin.text = String.format(Locale.US, "%02d", (totalSec / 60).toInt()); tvSec.text = String.format(Locale.US, "%02d", (totalSec % 60).toInt()) }

    private fun findOrAddTiming(pos: Int): SettingsActivity.Timing {
        val sorted = timings.sortedBy { it.time }; val exactTiming = sorted.find { Math.abs(it.time - pos) < 500 }
        if (exactTiming != null) return exactTiming
        val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
        val newTiming = SettingsActivity.Timing(pos, currentTiming?.max ?: 0L, 0L, currentTiming?.step ?: 0L, currentTiming?.multType ?: 0, currentTiming?.multVal ?: 1, currentTiming?.isEnabled ?: false)
        timings.add(newTiming); timings.sortBy { it.time }; return newTiming
    }

    private fun updateExerciseControlsUI() {
        if (!isFromSettings || layoutExerciseControls.visibility != View.VISIBLE) return
        val pos = player.currentPosition.toInt(); val sorted = timings.sortedBy { it.time }; val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time } ?: return
        val nextTiming = sorted.filter { it.time > pos }.minByOrNull { it.time }; val totalDur = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt()
        tvControlSegmentLabel.text = "${formatTime(currentTiming.time)} - ${if (nextTiming != null) formatTime(nextTiming.time) else formatTime(totalDur)}"
        pendingEnabled = currentTiming.isEnabled; pendingMultType = currentTiming.multType; pendingMultVal = currentTiming.multVal; swExerciseEnabled.isChecked = pendingEnabled; setFieldsFromMs(currentTiming.max, tvControlMaxMin, tvControlMaxSec); setFieldsFromMs(currentTiming.step, tvControlStepMin, tvControlStepSec); tvControlMult.text = when(pendingMultType) { 1 -> "x$pendingMultVal"; 2 -> "№ файла $fileNumForDisplay"; else -> "--" }
        updateExerciseControlsVisibility(pendingEnabled, currentTiming.max > 0); btnControlDelete.visibility = if (currentTiming.time == 0) View.GONE else View.VISIBLE
    }

    private fun updateExerciseControlsVisibility(swOn: Boolean, maxOn: Boolean) { tvControlMaxMin.isEnabled = swOn; tvControlMaxSec.isEnabled = swOn; tvControlMaxMin.alpha = if (swOn) 1f else 0.4f; tvControlMaxSec.alpha = if (swOn) 1f else 0.4f; val stepMultEnabled = swOn && maxOn; tvControlStepMin.isEnabled = stepMultEnabled; tvControlStepSec.isEnabled = stepMultEnabled; tvControlStepMin.alpha = if (stepMultEnabled) 1f else 0.4f; tvControlStepSec.alpha = if (stepMultEnabled) 1f else 0.4f; tvControlMult.isEnabled = stepMultEnabled; tvControlMult.alpha = if (stepMultEnabled) 1f else 0.4f }

    override fun onBackPressed() { saveCurrentPositionToPrefs(player.currentPosition.toInt()); super.onBackPressed() }

    private fun resetTaskTimer() { activeTiming = null; activeTimingTime = -1; activeSegmentEnd = -1; segmentPlayedMs = 0L; lastVideoPos = player.currentPosition.toInt(); pendingSkipZeroCurr = false; lastTickRealtime = SystemClock.elapsedRealtime(); isCompletionSoundPlayed = false }

    private fun clampSeekTarget(targetMs: Int): Int { val d = player.duration; if (d == C.TIME_UNSET || d <= 0) return targetMs.coerceAtLeast(0); return targetMs.coerceIn(0, (d.toInt() - 1).coerceAtLeast(0)) }

    private fun handlePlayerEnded() {
        val sorted = timings.sortedBy { it.time }; val d = player.duration
        if (d != C.TIME_UNSET) {
            val pos = d.toInt(); val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time }
            if (currentTiming != null && currentTiming.isEnabled && currentTiming.max > 0L) {
                val currMs = if (currentTiming.curr == -1L) 0L else Math.abs(currentTiming.curr).coerceAtLeast(0L)
                if (segmentPlayedMs < currMs) { beginSeek(clampSeekTarget(currentTiming.time + 10)); player.play() ; return }
            }
        }
        clearActiveSegmentState(); saveCurrentPositionToPrefs(0); finish()
    }

    private fun updateStopwatchDisplay() { if (!isStopwatchVisible || !stopwatchRunning) return; tvBottomStopwatchTime.text = formatTime((SystemClock.elapsedRealtime() - stopwatchBaseTime).toInt()) }

    private fun processTaskLogic(pos: Int, isPlaying: Boolean) {
        val sorted = timings.sortedBy { it.time }; val currentTiming = sorted.filter { it.time <= pos }.maxByOrNull { it.time } ?: return
        if (!currentTiming.isEnabled) { layoutCounterContainer.visibility = View.GONE; activeTiming = null; activeTimingTime = -1; activeSegmentEnd = -1; segmentPlayedMs = 0L; lastVideoPos = pos; lastTickRealtime = SystemClock.elapsedRealtime(); return }
        val nextTiming = sorted.filter { it.time > currentTiming.time }.minByOrNull { it.time }; val segmentStart = currentTiming.time; val totalDur = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt(); val segmentEnd = nextTiming?.time ?: totalDur
        if (currentTiming.max <= 0L) {
            layoutCounterContainer.visibility = View.GONE
            if (isPlaying && !isSeeking) {
                if (nextTiming != null) { val target = clampSeekTarget(segmentEnd + 10); if (pos < target - 20) { clearActiveSegmentState(); beginSeek(target); return } }
                else { clearActiveSegmentState(); saveCurrentPositionToPrefs(0); finish(); return }
            }
            activeTiming = null; activeTimingTime = -1; activeSegmentEnd = -1; segmentPlayedMs = 0L; lastVideoPos = pos; lastTickRealtime = SystemClock.elapsedRealtime(); return
        }
        if (activeTimingTime != currentTiming.time) { activeTiming = currentTiming; activeTimingTime = currentTiming.time; activeSegmentEnd = segmentEnd; if (!isInitialSegmentRestored && initialSegmentPlayed > 0) { segmentPlayedMs = initialSegmentPlayed; isInitialSegmentRestored = true } else { segmentPlayedMs = 0L }; lastVideoPos = pos; pendingSkipZeroCurr = false; lastTickRealtime = SystemClock.elapsedRealtime(); isCompletionSoundPlayed = false }
        layoutCounterContainer.visibility = View.VISIBLE
        val currMs = if (currentTiming.curr == -1L) 0L else Math.abs(currentTiming.curr).coerceAtLeast(0L)
        if (currMs == 0L) {
            if (nextTiming != null && segmentEnd > segmentStart) { if (isPlaying && !isSeeking) { val target = clampSeekTarget(segmentEnd + 10); if (pos < target - 50) { clearActiveSegmentState(); beginSeek(target) } } }
            else if (nextTiming == null) { if (isPlaying && !isSeeking) { saveCurrentPositionToPrefs(0); finish() } }
            tvExerciseCounter.text = formatTime(0); circularTimer.progress = 0; layoutStickContainer.visibility = View.GONE; lastVideoPos = pos; lastTickRealtime = SystemClock.elapsedRealtime(); return
        }
        val now = SystemClock.elapsedRealtime(); if (isPlaying && !isSeeking) { val dt = now - lastTickRealtime; if (dt > 0) segmentPlayedMs += dt }; lastTickRealtime = now; lastVideoPos = pos
        val remainingMs = (currMs - segmentPlayedMs).coerceAtLeast(0L); tvExerciseCounter.text = formatTime(remainingMs.toInt()); circularTimer.progress = ((remainingMs.toFloat() / currMs.toFloat()) * 100f).toInt().coerceIn(0, 100)
        val stepMs = currentTiming.step.coerceAtLeast(0L); if (stepMs > 0) { layoutStickContainer.rotation = -((segmentPlayedMs % stepMs).toFloat() / stepMs.toFloat()) * 360f; layoutStickContainer.visibility = View.VISIBLE } else layoutStickContainer.visibility = View.GONE
        if (segmentPlayedMs >= currMs) {
            if (!isCompletionSoundPlayed) {
                completionPlayer?.start()
                isCompletionSoundPlayed = true
            }
            if (nextTiming != null) { val target = clampSeekTarget(segmentEnd + 10); if (!isSeeking) { clearActiveSegmentState(); beginSeek(target) } } else { if (!isSeeking) { saveCurrentPositionToPrefs(0); finish() } }; return
        }
        if (isPlaying && !isSeeking) { val timeLeftInSegment = (segmentEnd - pos).coerceAtLeast(0); if (timeLeftInSegment <= 150 && remainingMs > (timeLeftInSegment + 50L)) { userSeekAnchorPos = null; beginSeek(clampSeekTarget(segmentStart + 10)) } }
    }

    private fun updateUIState() { val pos = player.currentPosition.toInt(); seekBar.progress = pos; updateTimeDisplay(); processTaskLogic(pos, player.isPlaying) }

    private fun addDefaultTimings(duration: Int) {
        var changed = false; if (timings.none { it.time == 0 }) { timings.add(SettingsActivity.Timing(0, 0, 0, 0, 1, 1, false)); changed = true }; val initialSize = timings.size; timings.removeAll { it.time >= duration - 1000 && it.time > 0 }; if (timings.size != initialSize) changed = true
        if (changed) { timings.sortBy { it.time }; saveTimingsToConfig() }
    }

    private fun drawTicks(duration: Int) { layoutTicks.post { layoutTicks.removeAllViews(); val width = layoutTicks.width; if (width <= 0 || duration <= 0) return@post; timings.forEach { timing -> if (timing.time <= 0 || timing.time >= duration) return@forEach; val tick = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0.7f }; val params = FrameLayout.LayoutParams(2, FrameLayout.LayoutParams.MATCH_PARENT); params.leftMargin = (timing.time.toFloat() / duration * width).toInt() ; layoutTicks.addView(tick, params) } } }

    private fun findConfigFile(folder: DocumentFile): DocumentFile? { folder.findFile("texfit.cfg")?.let { return it }; return folder.listFiles().firstOrNull { val name = it.name ?: return@firstOrNull false; name == "texfit.cfg" || name.startsWith("texfit.cfg.") } }

    private fun saveCurrentPositionToPrefs(pos: Int) {
        if (videoItemId.isEmpty() || isFromSettings) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playlistStr = prefs.getString(KEY_PLAYLIST, null) ?: return
        try {
            val titlesArray = JSONArray(playlistStr)
            for (i in 0 until titlesArray.length()) {
                val entry = titlesArray.optJSONArray(i)
                if (entry != null && entry.optString(0) == videoItemId) {
                    entry.put(2, pos)
                    entry.put(3, segmentPlayedMs)
                    break
                }
            }
            prefs.edit().putString(KEY_PLAYLIST, titlesArray.toString()).apply()
        } catch (e: Exception) { Log.e("VideoPlayer", "Save pos to prefs error", e) }
    }

    private fun loadTimingsFromConfig() {
        try {
            val folderUriStr = getSharedPreferences("TexfitPrefs", Context.MODE_PRIVATE).getString("selectedFolderUri", null) ?: return
            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return; val configFile = findConfigFile(folder) ?: return
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                val json = JSONObject(inputStream.bufferedReader().readText()); currStepConfig = json.optInt("curr_step", 1); val videoItems = json.optJSONArray("video_items") ?: return
                for (i in 0 until videoItems.length()) { val item = videoItems.getJSONObject(i); if (item.optString("id") == videoItemId) { videoFileName = item.optString("f_n"); fileNumForDisplay = item.optString("n_f", "000"); val tArr = item.optJSONArray("timings"); timings.clear(); if (tArr != null) for (j in 0 until tArr.length()) { val tObj = tArr.getJSONObject(j); timings.add(SettingsActivity.Timing(tObj.getInt("t"), tObj.optLong("m", 0), tObj.optLong("c", 0), tObj.optLong("s", 0), tObj.optInt("mt", 0), tObj.optInt("mv", 1), tObj.optBoolean("en", true))) }; break } }
            }
        } catch (e: Exception) { Log.e("VideoPlayer", "Load timings error", e) }
    }

    private fun saveTimingsToConfig() {
        try {
            val folderUriStr = getSharedPreferences("TexfitPrefs", Context.MODE_PRIVATE).getString("selectedFolderUri", null) ?: return
            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return; val configFile = findConfigFile(folder) ?: return
            val json: JSONObject; contentResolver.openInputStream(configFile.uri)?.use { inputStream -> json = JSONObject(inputStream.bufferedReader().readText()) } ?: return
            val videoItems = json.optJSONArray("video_items") ?: return
            for (i in 0 until videoItems.length()) { val item = videoItems.optJSONObject(i) ?: continue; if (item.optString("id") == videoItemId) { val tArr = JSONArray(); timings.forEach { val tObj = JSONObject(); tObj.put("t", it.time); tObj.put("m", it.max); tObj.put("c", it.curr); tObj.put("s", it.step); tObj.put("mt", it.multType); tObj.put("mv", it.multVal); tObj.put("en", it.isEnabled); tArr.put(tObj) }; item.put("timings", tArr); break } }
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { OutputStreamWriter(it).use { writer -> writer.write(json.toString(4)) } }
        } catch (e: Exception) { Log.e("VideoPlayer", "Save timings error", e) }
    }

    private fun showControls(show: Boolean) { val v = if (show) View.VISIBLE else View.GONE; layoutPauseInfo.visibility = v; btnStop.visibility = v; if (isFromSettings) { layoutExerciseControls.visibility = v; btnFineBack.visibility = v; btnFineForward.visibility = v; if (show) updateExerciseControlsUI() } }
    private fun updateTimeDisplay() { val current = player.currentPosition.toInt(); val total = if (player.duration == C.TIME_UNSET) 0 else player.duration.toInt(); if (total > 0) tvTime.text = "${formatTime(current)} / ${formatTime(total)}" }
    private fun formatTime(millis: Int): String { val totalSec = millis / 1000; return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60) }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUI() }
    private fun hideSystemUI() { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN) }
    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(updateSeekRunnable); try { completionPlayer?.release(); completionPlayer = null; playerView.player = null; player.release() } catch (_: Exception) {} }
}

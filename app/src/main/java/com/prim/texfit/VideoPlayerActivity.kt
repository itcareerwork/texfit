package com.prim.texfit

import android.app.Activity
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale

class VideoPlayerActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var seekBar: SeekBar
    private lateinit var btnStop: Button
    private lateinit var tvTime: TextView
    private lateinit var layoutPauseInfo: View
    private lateinit var clickInterceptor: View

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            try {
                if (videoView.isPlaying) {
                    seekBar.progress = videoView.currentPosition
                    updateTimeDisplay()
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
        tvTime = findViewById(R.id.tv_time)
        layoutPauseInfo = findViewById(R.id.layout_pause_info)
        clickInterceptor = findViewById(R.id.click_interceptor)

        val videoUri = intent.getParcelableExtra<Uri>("video_uri")
        if (videoUri != null) {
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener { mp: MediaPlayer ->
                seekBar.max = mp.duration
                videoView.start()
                handler.post(updateSeekRunnable)
            }
            videoView.setOnCompletionListener {
                finish()
            }
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

        btnStop.setOnClickListener {
            videoView.stopPlayback()
            finish()
        }

        // Убираем искусственный шаг. Стандартный SeekBar работает максимально точно по пикселям.
        // Чтобы видео переходило точно, используем текущее значение progress без округления.
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    updateTimeDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showControls(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        layoutPauseInfo.visibility = visibility
        btnStop.visibility = visibility
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

package com.prim.texfit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prim.texfit.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.Semaphore

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = PlaylistAdapter()
    private var isFirstResume = true
    private lateinit var db: AppDatabase

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val TAG = "MainActivity"
        
        private const val KEY_PLAYLIST = "playlist_data"
        private const val KEY_TRAINING_TIME = "training_time_val"
        private const val KEY_LAST_LAUNCH = "last_auto_launch_ts"
        private val thumbnailDecodeSemaphore = Semaphore(2)
    }

    data class PlaylistItem(val id: String, val uri: Uri, val isWatched: Boolean, val displayName: String, val lastPos: Int, val segmentPlayed: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        db = AppDatabase.getDatabase(this)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (isFirstResume) {
                withContext(Dispatchers.IO) {
                    checkAndPerformAutoLaunch()
                }
                isFirstResume = false
            }
            loadPlaylistFromConfig()
        }
    }

    private suspend fun checkAndPerformAutoLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timeStr = prefs.getString(KEY_TRAINING_TIME, "00:00") ?: "00:00"
        val lastLaunchTs = prefs.getLong(KEY_LAST_LAUNCH, 0L)

        if (timeStr == "00:00") return

        val parts = timeStr.split(":")
        if (parts.size != 2) return
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        val nowMs = now.timeInMillis
        val targetMs = target.timeInMillis

        if (nowMs >= targetMs && lastLaunchTs < targetMs) {
            performDailyUpdate()
            prefs.edit().putLong(KEY_LAST_LAUNCH, nowMs).apply()
        }
    }

    private suspend fun performDailyUpdate() {
        try {
            SettingsActivity.applyLaunchLogicDB(this, db)
        } catch (e: Exception) { Log.e(TAG, "Daily update failed", e) }
    }

    private fun loadPlaylistFromConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playlistJsonStr = prefs.getString(KEY_PLAYLIST, null) ?: return
        val folderUriStr = prefs.getString(SELECTED_FOLDER_URI_KEY, null) ?: return
        
        lifecycleScope.launch {
            val playlist = withContext(Dispatchers.IO) {
                try {
                    val titlesArray = JSONArray(playlistJsonStr)
                    val videoItems = db.videoItemDao().getAll().associateBy { it.id }

                    val folderUri = Uri.parse(folderUriStr)
                    val folder = DocumentFile.fromTreeUri(this@MainActivity, folderUri) ?: return@withContext null
                    val resultList = mutableListOf<PlaylistItem>()
                    for (i in 0 until titlesArray.length()) {
                        val entry = titlesArray.optJSONArray(i) ?: continue
                        val id = entry.optString(0)
                        val status = entry.optInt(1, 0)
                        val lastPos = entry.optInt(2, 0) 
                        val segmentPlayed = entry.optLong(3, 0L)
                        
                        val itemEntity = videoItems[id] ?: continue
                        val fileName = itemEntity.fileName
                        var displayName = itemEntity.customName
                        if (displayName.isEmpty()) displayName = fileName

                        if (fileName.isNotEmpty()) {
                            folder.findFile(fileName)?.uri?.let { uri ->
                                resultList.add(PlaylistItem(id, uri, status == 1, displayName, lastPos, segmentPlayed))
                            }
                        }
                    }
                    resultList
                } catch (e: Exception) {
                    Log.e(TAG, "Playlist loading failed", e)
                    null
                }
            }
            
            playlist?.let {
                adapter.submitList(it)
            }
        }
    }

    private fun toggleWatchedStatus(position: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playlistJsonStr = prefs.getString(KEY_PLAYLIST, null) ?: return
        
        try {
            val titlesArray = JSONArray(playlistJsonStr)
            val entry = titlesArray.optJSONArray(position) ?: return
            
            val currentStatus = entry.optInt(1, 0)
            entry.put(1, if (currentStatus == 1) 0 else 1)

            prefs.edit().putString(KEY_PLAYLIST, titlesArray.toString()).apply()
            loadPlaylistFromConfig()
        } catch (e: Exception) { Log.e(TAG, "Status save failed", e) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                overridePendingTransition(0, 0)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private inner class PlaylistAdapter :
        ListAdapter<PlaylistItem, PlaylistAdapter.ViewHolder>(PlaylistDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_video, parent, false)
            view.layoutParams.height = parent.resources.displayMetrics.heightPixels / 3
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.tvDisplayName.text = item.displayName

            holder.bindThumbnail(item)

            holder.imageView.alpha = if (item.isWatched) 0.3f else 1.0f
            holder.ivCheck.setImageResource(if (item.isWatched) android.R.drawable.checkbox_on_background else android.R.drawable.checkbox_off_background)

            holder.ivCheck.setOnClickListener { toggleWatchedStatus(position) }
            holder.imageView.setOnClickListener {
                val intent = Intent(this@MainActivity, VideoPlayerActivity::class.java)
                intent.putExtra("video_uri", item.uri)
                intent.putExtra("video_item_id", item.id)
                intent.putExtra("last_pos", item.lastPos) 
                intent.putExtra("segment_played", item.segmentPlayed)
                intent.putExtra("item_index", position) 
                startActivity(intent)
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.iv_thumbnail)
            val ivCheck: ImageView = itemView.findViewById(R.id.iv_watched_check)
            val tvDisplayName: TextView = itemView.findViewById(R.id.tv_debug_info)
            private var thumbnailJob: Job? = null
            private var boundItemId: String? = null

            fun bindThumbnail(item: PlaylistItem) {
                boundItemId = item.id
                thumbnailJob?.cancel()
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)

                thumbnailJob = lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        thumbnailDecodeSemaphore.acquire()
                        try {
                            var retriever: MediaMetadataRetriever? = null
                            try {
                                retriever = MediaMetadataRetriever()
                                retriever.setDataSource(this@MainActivity, item.uri)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                    retriever.getScaledFrameAtTime(
                                        1000000,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                        320,
                                        180
                                    )
                                } else {
                                    retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                }
                            } catch (_: Exception) {
                                null
                            } finally {
                                try { retriever?.release() } catch (_: Exception) {}
                            }
                        } finally {
                            thumbnailDecodeSemaphore.release()
                        }
                    }

                    if (boundItemId == item.id) {
                        if (bitmap != null) imageView.setImageBitmap(bitmap)
                        else imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                }
            }
        }
    }
}

class PlaylistDiffCallback : DiffUtil.ItemCallback<MainActivity.PlaylistItem>() {
    override fun areItemsTheSame(oldItem: MainActivity.PlaylistItem, newItem: MainActivity.PlaylistItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: MainActivity.PlaylistItem, newItem: MainActivity.PlaylistItem) = oldItem == newItem
}

package com.prim.texfit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = PlaylistAdapter()
    private var isFirstResume = true

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val CONFIG_FILE_NAME = "texfit.cfg"
        private const val TAG = "MainActivity"
        
        // Ключи для SharedPreferences
        private const val KEY_PLAYLIST = "playlist_data"
        private const val KEY_TRAINING_TIME = "training_time_val"
        private const val KEY_LAST_LAUNCH = "last_auto_launch_ts"
    }

    data class PlaylistItem(val id: String, val uri: Uri, val isWatched: Boolean, val displayName: String, val lastPos: Int, val segmentPlayed: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            checkAndPerformAutoLaunch()
            isFirstResume = false
        }
        loadPlaylistFromConfig()
    }

    private fun checkAndPerformAutoLaunch() {
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

    private fun performDailyUpdate() {
        val folderUriStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(SELECTED_FOLDER_URI_KEY, null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
        val configFile = findConfigFile(folder) ?: return

        try {
            val json: JSONObject
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                json = JSONObject(inputStream.bufferedReader().readText())
            } ?: return

            // Применяем логику и сохраняем плейлист во внутреннюю память
            val updatedJson = SettingsActivity.applyLaunchLogic(json, this)
            
            // Сохраняем обновленные video_items (с новыми прогрессами curr) обратно в файл
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer -> writer.write(updatedJson.toString(4)) }
            }
        } catch (e: Exception) { Log.e(TAG, "Daily update failed", e) }
    }

    private fun loadPlaylistFromConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playlistJsonStr = prefs.getString(KEY_PLAYLIST, null) ?: return
        
        val folderUriStr = prefs.getString(SELECTED_FOLDER_URI_KEY, null) ?: return
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
        val configFile = findConfigFile(folder) ?: return

        try {
            val titlesArray = JSONArray(playlistJsonStr)
            
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                val json = JSONObject(inputStream.bufferedReader().readText())
                val videoItemsArray = json.optJSONArray("video_items") ?: return

                val videoItemsMap = mutableMapOf<String, JSONObject>()
                for (i in 0 until videoItemsArray.length()) {
                    val item = videoItemsArray.getJSONObject(i)
                    videoItemsMap[item.optString("id")] = item
                }

                val playlist = mutableListOf<PlaylistItem>()
                for (i in 0 until titlesArray.length()) {
                    val entry = titlesArray.optJSONArray(i) ?: continue
                    val id = entry.optString(0)
                    val status = entry.optInt(1, 0)
                    val lastPos = entry.optInt(2, 0) 
                    val segmentPlayed = entry.optLong(3, 0L)
                    
                    val itemJson = videoItemsMap[id] ?: continue
                    val fileName = itemJson.optString("f_n")
                    var displayName = itemJson.optString("c_n")
                    if (displayName.isEmpty()) displayName = fileName

                    if (fileName.isNotEmpty()) {
                        folder.findFile(fileName)?.uri?.let { uri ->
                            playlist.add(PlaylistItem(id, uri, status == 1, displayName, lastPos, segmentPlayed))
                        }
                    }
                }
                adapter.submitList(playlist)
            }
        } catch (e: Exception) { Log.e(TAG, "Playlist loading failed", e) }
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
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun findConfigFile(folder: DocumentFile): DocumentFile? {
        folder.findFile(CONFIG_FILE_NAME)?.let { return it }
        return folder.listFiles().firstOrNull { file ->
            val name = file.name ?: return@firstOrNull false
            name == CONFIG_FILE_NAME || name.startsWith("$CONFIG_FILE_NAME.")
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
            
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this@MainActivity, item.uri)
                val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) holder.imageView.setImageBitmap(bitmap)
                else holder.imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            } catch (e: Exception) {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }

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
        }
    }

    private class PlaylistDiffCallback : DiffUtil.ItemCallback<PlaylistItem>() {
        override fun areItemsTheSame(oldItem: PlaylistItem, newItem: PlaylistItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PlaylistItem, newItem: PlaylistItem) = oldItem == newItem
    }
}

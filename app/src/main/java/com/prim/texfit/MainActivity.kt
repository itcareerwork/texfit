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
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = PlaylistAdapter()

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val CONFIG_FILE_NAME = "texfit.cfg"
        private const val TAG = "MainActivity"
    }

    data class PlaylistItem(val uri: Uri, val isWatched: Boolean, val debugInfo: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadPlaylistFromConfig()
    }

    private fun loadPlaylistFromConfig() {
        val folderUriStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SELECTED_FOLDER_URI_KEY, null) ?: return
        
        val folderUri = Uri.parse(folderUriStr)
        val folder = DocumentFile.fromTreeUri(this, folderUri) ?: return
        val configFile = findConfigFile(folder) ?: return

        try {
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                val json = JSONObject(inputStream.bufferedReader().readText())
                val titlesArray = json.optJSONArray("titles")
                val videoItemsArray = json.optJSONArray("video_items")
                
                val sessionOptions = mutableListOf<String>()
                json.optJSONArray("session_options")?.let { arr ->
                    for (i in 0 until arr.length()) sessionOptions.add(arr.getString(i))
                }
                val exerciseOptions = mutableListOf<String>()
                json.optJSONArray("exercise_options")?.let { arr ->
                    for (i in 0 until arr.length()) exerciseOptions.add(arr.getString(i))
                }

                if (titlesArray == null || videoItemsArray == null) {
                    adapter.submitList(emptyList())
                    return
                }

                val playlist = mutableListOf<PlaylistItem>()
                for (i in 0 until titlesArray.length()) {
                    val entry = titlesArray.optJSONArray(i) ?: continue
                    val idx = entry.optInt(0, -1)
                    val status = entry.optInt(1, 0)
                    
                    if (idx in 0 until videoItemsArray.length()) {
                        val itemJson = videoItemsArray.getJSONObject(idx)
                        val fileName = itemJson.optString("f_n")
                        
                        val sIdx = itemJson.optInt("s_idx", -1)
                        val eIdx = itemJson.optInt("e_idx", -1)
                        val fullSession = if (sIdx in sessionOptions.indices) sessionOptions[sIdx] else ""
                        val sessionName = fullSession.substringAfter(" ", "")
                        val exerciseName = if (eIdx in exerciseOptions.indices) exerciseOptions[eIdx] else ""
                        val numExercise = itemJson.optString("n_e")
                        val numFile = itemJson.optString("n_f")
                        
                        val debugStr = "|$sessionName|$numExercise $exerciseName|$numFile|"

                        if (fileName.isNotEmpty()) {
                            folder.findFile(fileName)?.uri?.let { uri ->
                                playlist.add(PlaylistItem(uri, status == 1, debugStr))
                            }
                        }
                    }
                }
                adapter.submitList(playlist)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки плейлиста", e)
        }
    }

    private fun toggleWatchedStatus(position: Int) {
        val folderUriStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SELECTED_FOLDER_URI_KEY, null) ?: return
        val folderUri = Uri.parse(folderUriStr)
        val folder = DocumentFile.fromTreeUri(this, folderUri) ?: return
        val configFile = findConfigFile(folder) ?: return

        try {
            val json: JSONObject
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                json = JSONObject(inputStream.bufferedReader().readText())
            } ?: return

            val titlesArray = json.optJSONArray("titles") ?: return
            val entry = titlesArray.optJSONArray(position) ?: return
            
            val currentStatus = entry.optInt(1, 0)
            entry.put(1, if (currentStatus == 1) 0 else 1)

            contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(json.toString(4))
                }
            }
            loadPlaylistFromConfig()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения статуса", e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
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
            val screenHeight = parent.resources.displayMetrics.heightPixels
            view.layoutParams.height = screenHeight / 3
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            
            holder.tvDebug.text = item.debugInfo
            
            try {
                val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(item.uri, Size(640, 480), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, item.uri)
                        retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        retriever.release()
                    }
                }
                holder.imageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            // Засветление при просмотре (увеличение прозрачности на белом фоне)
            holder.imageView.alpha = if (item.isWatched) 0.3f else 1.0f

            holder.ivCheck.setImageResource(
                if (item.isWatched) android.R.drawable.checkbox_on_background 
                else android.R.drawable.checkbox_off_background
            )

            holder.ivCheck.setOnClickListener {
                toggleWatchedStatus(position)
            }

            holder.imageView.setOnClickListener {
                val intent = Intent(this@MainActivity, VideoPlayerActivity::class.java)
                intent.putExtra("video_uri", item.uri)
                startActivity(intent)
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.iv_thumbnail)
            val ivCheck: ImageView = itemView.findViewById(R.id.iv_watched_check)
            val tvDebug: TextView = itemView.findViewById(R.id.tv_debug_info)
        }
    }

    private class PlaylistDiffCallback : DiffUtil.ItemCallback<PlaylistItem>() {
        override fun areItemsTheSame(oldItem: PlaylistItem, newItem: PlaylistItem): Boolean = 
            oldItem.uri == newItem.uri
        override fun areContentsTheSame(oldItem: PlaylistItem, newItem: PlaylistItem): Boolean = 
            oldItem == newItem
    }
}

package com.prim.texfit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = PlaylistAdapter()

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val CONFIG_FILE_NAME = "texfit.cfg"
        private const val TAG = "MainActivity"
    }

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
                val titlesList = mutableListOf<String>()
                if (titlesArray != null) {
                    for (i in 0 until titlesArray.length()) {
                        titlesList.add(titlesArray.getString(i))
                    }
                }
                adapter.submitList(titlesList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки плейлиста", e)
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
        // 1. Точное имя
        folder.findFile(CONFIG_FILE_NAME)?.let { return it }

        // 2. Варианты, которые создаёт SAF: texfit.cfg.json, texfit.cfg (1).json и т.п.
        return folder.listFiles().firstOrNull { file ->
            val name = file.name ?: return@firstOrNull false
            name == CONFIG_FILE_NAME || name.startsWith("$CONFIG_FILE_NAME.")
        }
    }
}

private class PlaylistAdapter :
    ListAdapter<String, PlaylistAdapter.ViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val textView = TextView(parent.context).apply {
            // Поведение и стиль оставляем максимально близким к simple_list_item_1
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 16, 32, 16)
            textSize = 16f
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = getItem(position)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView as TextView
    }
}

private class PlaylistDiffCallback : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
}

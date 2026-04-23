package com.prim.texfit

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.system.exitProcess

class SettingsActivity : AppCompatActivity() {

    data class ConfigOption(val id: String, var name: String) {
        override fun toString(): String = name
    }

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val TAG = "SettingsActivity"
        private const val CONFIG_FILE_NAME = "texfit.cfg"
        private const val EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI"
        
        private const val KEY_PLAYLIST = "playlist_data"
        private const val KEY_TRAINING_TIME = "training_time_val"

        private fun generateId(): String = (100000..999999).random().toString()
        private fun extractNumber(s: String): Int = s.substringBefore(" ").toIntOrNull() ?: Int.MAX_VALUE

        // ХЕЛПЕРЫ ДЛЯ SharedPreferences (Горячие данные)
        fun getTimingCurr(context: Context, videoId: String, time: Int): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("curr_${videoId}_$time", -1L)
        }
        fun saveTimingCurr(context: Context, videoId: String, time: Int, curr: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putLong("curr_${videoId}_$time", curr) }
        }
        fun getCategoryState(context: Context, exId: String): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("cat_$exId", "000") ?: "000"
        }
        fun saveCategoryState(context: Context, exId: String, state: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString("cat_$exId", state) }
        }
        fun getResetState(context: Context, exId: String): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("reset_$exId", "001") ?: "001"
        }
        fun saveResetState(context: Context, exId: String, state: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString("reset_$exId", state) }
        }

        fun applyLaunchLogic(json: JSONObject, context: Context): JSONObject {
            val currStep = json.optInt("curr_step", 1)
            
            val sessionOptions = mutableListOf<ConfigOption>()
            json.optJSONArray("session_options")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sessionOptions.add(ConfigOption(obj.getString("id"), obj.getString("name")))
                }
            }
            
            val videoItemsArray = json.optJSONArray("video_items") ?: JSONArray()
            val allItems = mutableListOf<VideoItem>()
            for (i in 0 until videoItemsArray.length()) {
                allItems.add(VideoItem.fromJson(videoItemsArray.getJSONObject(i), context))
            }

            val sourceTable = allItems.filter { it.isActive }.sortedWith(compareBy(
                { item -> extractNumber(sessionOptions.find { it.id == item.sessionId }?.name ?: "") },
                { it.numExercise.toIntOrNull() ?: 0 },
                { it.numFile.toIntOrNull() ?: 0 }
            ))

            val selectedItems = mutableListOf<VideoItem>()
            val changedExercises = mutableSetOf<String>()
            val slotGroups = sourceTable.groupBy { it.exerciseId }

            for (group in slotGroups.values) {
                val firstRow = group.first()
                val exId = firstRow.exerciseId
                val currentState = getCategoryState(context, exId).toIntOrNull() ?: 0
                var nextStep = currentState + 1
                
                val exerciseFiles = sourceTable.filter { it.exerciseId == exId && it.numFile.isNotEmpty() }
                if (exerciseFiles.isEmpty()) continue
                val limit = exerciseFiles.maxOf { it.numFile.toIntOrNull() ?: 0 }
                val resetVal = getResetState(context, exId).toIntOrNull() ?: 1
                if (nextStep > limit) nextStep = resetVal
                
                for (row in group) {
                    if (nextStep == (row.numFile.toIntOrNull() ?: 0)) {
                        selectedItems.add(row)
                        saveCategoryState(context, exId, String.format(Locale.US, "%03d", nextStep))
                        changedExercises.add(exId)
                        break 
                    }
                }
            }

            json.optJSONArray("exercise_options")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val exId = arr.getJSONObject(i).getString("id")
                    if (exId !in changedExercises) {
                        val currentState = getCategoryState(context, exId).toIntOrNull() ?: 0
                        val exerciseFiles = sourceTable.filter { it.exerciseId == exId && it.numFile.isNotEmpty() }
                        if (exerciseFiles.isNotEmpty()) {
                            val limit = exerciseFiles.maxOf { it.numFile.toIntOrNull() ?: 0 }
                            val resetVal = getResetState(context, exId).toIntOrNull() ?: 1
                            var nextStep = currentState + 1
                            if (nextStep > limit) nextStep = resetVal
                            saveCategoryState(context, exId, String.format(Locale.US, "%03d", nextStep))
                        }
                    }
                }
            }

            selectedItems.forEach { item ->
                val fileNum = item.numFile.toIntOrNull() ?: 1
                item.timings.forEach { t ->
                    if (t.max > 0) {
                        val multiplier = when(t.multType) {
                            1 -> t.multVal.toLong()
                            2 -> fileNum.toLong()
                            else -> 1L
                        }
                        val newCurr = if (currStep == 0) {
                            when {
                                t.curr == -1L -> 0L
                                t.curr == 0L -> -t.step
                                t.curr < 0L -> t.step * multiplier
                                else -> t.curr + (t.step * multiplier)
                            }
                        } else {
                            if (t.curr < 0) t.step * multiplier else t.curr + (t.step * multiplier)
                        }
                        t.curr = newCurr.coerceAtMost(t.max)
                        saveTimingCurr(context, item.id, t.time, t.curr)
                    }
                }
            }

            val titlesArray = JSONArray()
            selectedItems.forEach { selected ->
                val entry = JSONArray()
                entry.put(selected.id) 
                entry.put(0) // status
                entry.put(0) // lastPos
                entry.put(0L) // segmentPlayedMs
                titlesArray.put(entry)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_PLAYLIST, titlesArray.toString())
            }

            return json
        }
    }

    private lateinit var selectedFolderPathTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoListAdapter
    private lateinit var tvSetTime: TextView
    private lateinit var etTopInput: EditText
    private lateinit var btnLaunch: Button
    private lateinit var btnHelp: ImageButton
    
    private lateinit var hColor: TextView
    private lateinit var hCat1: TextView
    private lateinit var hCat2: TextView
    private lateinit var hCat3: TextView
    private lateinit var hSize: TextView
    private lateinit var hNote: TextView

    private var sessionOptions = mutableListOf<ConfigOption>()
    private var exerciseOptions = mutableListOf<ConfigOption>()
    private var activeExercisesOrder = mutableListOf<String>()

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                saveSelectedFolderUri(it)
                displaySelectedFolder(it)
                loadUIFromConfig()
            } catch (e: Exception) { Log.e(TAG, getString(R.string.error_permission), e) }
        }
    }

    private val selectFileLauncher = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            val intent = super.createIntent(context, input)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getFolderUri()?.let { intent.putExtra(EXTRA_INITIAL_URI, it) }
            }
            return intent
        }
    }) { uri -> uri?.let { addSingleFile(uri) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = getString(R.string.settings_title) }

        selectedFolderPathTextView = findViewById(R.id.selected_folder_path_text_view)
        recyclerView = findViewById(R.id.mp4_files_recycler_view)
        tvSetTime = findViewById(R.id.tv_set_time)
        etTopInput = findViewById(R.id.et_top_input)
        btnLaunch = findViewById(R.id.btn_launch)
        btnHelp = findViewById(R.id.btn_help)
        
        hColor = findViewById(R.id.header_color); hCat1 = findViewById(R.id.header_cat1)
        hCat2 = findViewById(R.id.header_cat2); hCat3 = findViewById(R.id.header_cat3)
        hSize = findViewById(R.id.header_size); hNote = findViewById(R.id.header_note)

        btnLaunch.setOnClickListener { performLaunchStep() }
        btnHelp.setOnClickListener { showHelpDialog() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoListAdapter()
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_add_folder).setOnClickListener { showAddMenu(it) }
        tvSetTime.setOnClickListener { showTimePicker() }

        loadAndDisplaySelectedFolder()
        loadUIFromConfig()
    }

    private fun showHelpDialog() {
        val scroll = ScrollView(this)
        val textView = TextView(this).apply {
            setPadding(50, 40, 50, 40)
            textSize = 14f
            text = android.text.Html.fromHtml(getString(R.string.help_text), android.text.Html.FROM_HTML_MODE_COMPACT)
        }
        scroll.addView(textView)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.help_title)).setView(scroll).setPositiveButton(getString(R.string.dialog_ok), null).create()
        dialog.show()
        val lp = WindowManager.LayoutParams(); lp.copyFrom(dialog.window?.attributes); lp.width = (resources.displayMetrics.widthPixels * 0.9).toInt(); dialog.window?.attributes = lp
        tintDialogButtons(dialog)
    }

    override fun onResume() { super.onResume(); loadUIFromConfig() }

    private fun updateItemById(id: String, transformer: (VideoItem) -> VideoItem) {
        val folder = getFolderDocumentFile() ?: return
        val configFile = findOrCreateConfigFile(folder) ?: return
        val json = readConfigJson(configFile) ?: return
        val itemsArray = json.optJSONArray("video_items") ?: JSONArray()
        val items = mutableListOf<VideoItem>()
        for (i in 0 until itemsArray.length()) items.add(VideoItem.fromJson(itemsArray.getJSONObject(i), this))
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) {
            items[index] = transformer(items[index])
            saveToConfig(folder, items)
            loadUIFromConfig()
        }
    }

    private fun performLaunchStep() {
        val folder = getFolderDocumentFile() ?: return
        val configFile = findConfigFileForRead(folder) ?: return
        val json = readConfigJson(configFile) ?: return
        val updated = applyLaunchLogic(json, this)
        contentResolver.openOutputStream(configFile.uri, "wt")?.use { writer ->
            OutputStreamWriter(writer).use { it.write(updated.toString(4)) }
        }
        loadUIFromConfig()
        val playlistStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PLAYLIST, null)
        val count = if (playlistStr != null) JSONArray(playlistStr).length() else 0
        if (count == 0) Toast.makeText(this, getString(R.string.no_active_exercises), Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, getString(R.string.files_found_count, count), Toast.LENGTH_SHORT).show()
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return ""
        val units = arrayOf(getString(R.string.unit_b), getString(R.string.unit_kb), getString(R.string.unit_mb), getString(R.string.unit_gb), getString(R.string.unit_tb))
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun updateTopInputUI(items: List<VideoItem>? = null) {
        val currentItems = items ?: adapter.currentList
        val activeCompleteItems = currentItems.filter { it.isActive && it.isComplete() }
        val currentActiveSet = activeCompleteItems.map { it.exerciseId }.toSet()
        activeExercisesOrder.removeAll { it !in currentActiveSet }
        currentActiveSet.forEach { if (it !in activeExercisesOrder) activeExercisesOrder.add(it) }
        if (activeExercisesOrder.isEmpty()) { etTopInput.setText(""); return }
        val sb = StringBuilder("| ")
        activeExercisesOrder.forEach { exId ->
            val exName = exerciseOptions.find { it.id == exId }?.name ?: "???"
            val num = getCategoryState(this, exId)
            sb.append("$exName $num | ")
        }
        etTopInput.setText(sb.toString())
    }

    private fun showAddMenu(view: View) {
        val items = listOf(Triple(1, getString(R.string.menu_folder), R.drawable.ic_add_folder), Triple(2, getString(R.string.menu_file), R.drawable.ic_add_video), Triple(3, getString(R.string.menu_refresh), R.drawable.ic_refresh_custom), Triple(5, getString(R.string.menu_sort), R.drawable.ic_sort_custom), Triple(4, getString(R.string.menu_backup), R.drawable.ic_backup_custom))
        val adapter = object : ArrayAdapter<Triple<Int, String, Int>>(this, android.R.layout.select_dialog_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val item = getItem(position)!!
                v.text = item.second; v.setCompoundDrawablesWithIntrinsicBounds(item.third, 0, 0, 0); v.compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
                return v
            }
        }
        val listPopup = ListPopupWindow(this); listPopup.setAdapter(adapter); listPopup.anchorView = view; listPopup.width = (220 * resources.displayMetrics.density).toInt(); listPopup.isModal = true
        listPopup.setOnItemClickListener { _, _, position, _ ->
            when (items[position].first) { 1 -> selectFolderLauncher.launch(null); 2 -> selectFileLauncher.launch(arrayOf("video/mp4")); 3 -> performFullRefresh(); 4 -> showBackupOptionsDialog(); 5 -> performSort() }
            listPopup.dismiss()
        }
        listPopup.show()
    }

    private fun showBackupOptionsDialog() {
        val options = arrayOf(getString(R.string.backup_create), getString(R.string.backup_restore))
        val builder = AlertDialog.Builder(this).setTitle(getString(R.string.menu_backup)).setItems(options) { _, which -> when (which) { 0 -> performBackup(); 1 -> showRestoreDialog() } }.setNegativeButton(getString(R.string.dialog_cancel), null)
        val dialog = builder.create(); dialog.show(); tintDialogButtons(dialog)
    }

    private fun showRestoreDialog() {
        val folder = getFolderDocumentFile() ?: return
        val backupFiles = folder.listFiles().filter { it.name?.endsWith(".backup") == true }.sortedByDescending { it.lastModified() }
        if (backupFiles.isEmpty()) { Toast.makeText(this, getString(R.string.backup_not_found), Toast.LENGTH_SHORT).show(); return }
        val names = backupFiles.map { it.name ?: getString(R.string.unnamed) }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle(getString(R.string.backup_select_file)).setItems(names) { _, which -> restoreBackup(backupFiles[which]) }.setNegativeButton(getString(R.string.dialog_cancel), null)
        val dialog = builder.create(); dialog.show(); tintDialogButtons(dialog)
    }

    private fun performBackup() {
        val folder = getFolderDocumentFile() ?: return
        val configFile = findConfigFileForRead(folder) ?: return
        try {
            val content = contentResolver.openInputStream(configFile.uri)?.use { it.bufferedReader().readText() } ?: return
            val dateStr = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
            val backupName = "${CONFIG_FILE_NAME}_${dateStr}.backup"
            folder.findFile(backupName)?.delete()
            val backupFile = folder.createFile("application/octet-stream", backupName) ?: return
            contentResolver.openOutputStream(backupFile.uri)?.use { it.write(content.toByteArray()) }
            Toast.makeText(this, getString(R.string.backup_created, backupName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Log.e(TAG, "Backup error", e) }
    }

    private fun restoreBackup(backupFile: DocumentFile) {
        val folder = getFolderDocumentFile() ?: return
        try {
            val content = contentResolver.openInputStream(backupFile.uri)?.use { it.bufferedReader().readText() } ?: return
            val configFile = findOrCreateConfigFile(folder) ?: return
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { it.write(content.toByteArray()) }
            loadUIFromConfig(); Toast.makeText(this, getString(R.string.backup_restored, backupFile.name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Log.e(TAG, "Restore error", e) }
    }

    private fun addSingleFile(uri: Uri) {
        val folder = getFolderDocumentFile() ?: return
        try {
            val pickedFile = DocumentFile.fromSingleUri(this, uri) ?: return
            val name = pickedFile.name ?: "video.mp4"
            if (!name.endsWith(".mp4", ignoreCase = true)) { Toast.makeText(this, getString(R.string.file_not_mp4), Toast.LENGTH_SHORT).show(); return }
            val configFile = findOrCreateConfigFile(folder) ?: return
            val json = readConfigJson(configFile) ?: JSONObject()
            val array = json.optJSONArray("video_items") ?: JSONArray()
            val items = mutableListOf<VideoItem>()
            for (i in 0 until array.length()) items.add(VideoItem.fromJson(array.getJSONObject(i), this))
            items.add(VideoItem(id = generateId(), fileName = name, fileSizeRaw = pickedFile.length()))
            saveToConfig(folder, items)
            loadUIFromConfig()
        } catch (e: Exception) { Log.e(TAG, getString(R.string.error_saving), e) }
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            val timeStr = String.format(Locale.US, "%02d:%02d", h, m)
            tvSetTime.text = timeStr
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_TRAINING_TIME, timeStr)
            }
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }

    private fun saveSelectedFolderUri(uri: Uri) { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(SELECTED_FOLDER_URI_KEY, uri.toString()) } }
    private fun loadAndDisplaySelectedFolder() { getFolderUri()?.let { displaySelectedFolder(it) } }
    private fun displaySelectedFolder(uri: Uri) { selectedFolderPathTextView.text = uri.path ?: getString(R.string.folder_selected) }
    private fun getFolderUri(): Uri? = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(SELECTED_FOLDER_URI_KEY, null)?.toUri()

    private fun loadUIFromConfig() {
        val folderUri = getFolderUri() ?: return
        val folder = DocumentFile.fromTreeUri(this, folderUri) ?: return
        val configFile = findConfigFileForRead(folder) ?: return
        try {
            val json = readConfigJson(configFile) ?: return
            btnLaunch.visibility = if (json.optInt("button", 0) == 1) View.VISIBLE else View.GONE
            
            tvSetTime.text = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TRAINING_TIME, getString(R.string.time_default))
            
            hCat1.text = getString(R.string.header_session)
            hCat2.text = getString(R.string.header_exercise)
            hCat3.text = getString(R.string.header_name)
            hSize.text = getString(R.string.header_size_label)
            hNote.text = getString(R.string.header_note_label)
            
            sessionOptions = mutableListOf(); json.optJSONArray("session_options")?.let { arr ->
                for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); sessionOptions.add(ConfigOption(obj.getString("id"), obj.getString("name"))) }
            }
            sessionOptions.sortWith(compareBy { extractNumber(it.name) })

            exerciseOptions = mutableListOf(); json.optJSONArray("exercise_options")?.let { arr ->
                for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); exerciseOptions.add(ConfigOption(obj.getString("id"), obj.getString("name"))) }
            }
            exerciseOptions.sortWith(compareBy { extractNumber(it.name) })

            val array = json.optJSONArray("video_items") ?: JSONArray(); val items = mutableListOf<VideoItem>()
            for (i in 0 until array.length()) items.add(VideoItem.fromJson(array.getJSONObject(i), this))
            adapter.submitList(items); updateTopInputUI(items)
        } catch (e: Exception) { Log.e(TAG, getString(R.string.error_loading), e) }
    }

    private fun performFullRefresh() {
        val folder = getFolderDocumentFile() ?: return
        val configFile = findOrCreateConfigFile(folder) ?: return
        val json = readConfigJson(configFile) ?: JSONObject()
        val filesInFolder = folder.listFiles().filter { it.name?.endsWith(".mp4", ignoreCase = true) == true }
        val fileNamesInFolder = filesInFolder.map { it.name ?: "" }.toSet()
        val currentItemsArray = json.optJSONArray("video_items") ?: JSONArray()
        val currentItems = mutableListOf<VideoItem>()
        for (i in 0 until currentItemsArray.length()) currentItems.add(VideoItem.fromJson(currentItemsArray.getJSONObject(i), this))
        val updatedItems = currentItems.filter { it.fileName in fileNamesInFolder }.toMutableList()
        val existingNames = updatedItems.map { it.fileName }.toSet()
        filesInFolder.filter { (it.name ?: "") !in existingNames }.forEach { file ->
            updatedItems.add(VideoItem(id = generateId(), fileName = file.name ?: "", fileSizeRaw = file.length()))
        }
        saveToConfig(folder, updatedItems); loadUIFromConfig(); Toast.makeText(this, getString(R.string.updated), Toast.LENGTH_SHORT).show()
    }

    private fun performSort() {
        val folder = getFolderDocumentFile() ?: return
        val currentItems = adapter.currentList.toMutableList()
        val sortedItems = currentItems.sortedWith(compareByDescending<VideoItem> { it.isActive }
            .thenBy { item -> extractNumber(sessionOptions.find { it.id == item.sessionId }?.name ?: "") }
            .thenBy { it.numExercise.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.numFile.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.fileName })
        saveToConfig(folder, sortedItems); loadUIFromConfig(); Toast.makeText(this, getString(R.string.sorted_msg), Toast.LENGTH_SHORT).show()
    }

    private fun saveToConfig(folder: DocumentFile, items: List<VideoItem>) {
        try {
            val configFile = findOrCreateConfigFile(folder) ?: return
            val oldJson = readConfigJson(configFile) ?: JSONObject()
            
            // Создаем новый объект, чтобы гарантировать порядок вставки
            val json = JSONObject()
            json.put("button", oldJson.optInt("button", 0))
            json.put("curr_step", oldJson.optInt("curr_step", 1))
            
            val array = JSONArray(); items.forEach { array.put(it.toJson()) }; json.put("video_items", array)
            val sArr = JSONArray(); sessionOptions.forEach { sArr.put(JSONObject().apply { put("id", it.id); put("name", it.name) }) }; json.put("session_options", sArr)
            val eArr = JSONArray(); exerciseOptions.forEach { eArr.put(JSONObject().apply { put("id", it.id); put("name", it.name) }) }; json.put("exercise_options", eArr)
            
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { writer -> OutputStreamWriter(writer).use { it.write(json.toString(4)) } }
        } catch (e: Exception) { Log.e(TAG, getString(R.string.error_saving), e) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean { menuInflater.inflate(R.menu.settings_menu, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_exit -> { finishAffinity(); exitProcess(0) }
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    data class Timing(val time: Int, var max: Long = 0L, var curr: Long = 0L, var step: Long = 0L, var multType: Int = 0, var multVal: Int = 1, var isEnabled: Boolean = false)

    data class VideoItem(var id: String = generateId(), var sessionId: String = "", var exerciseId: String = "", var numExercise: String = "", var numFile: String = "", var fileName: String = "", var fileSizeRaw: Long = 0, var note: String = "", var timings: MutableList<Timing> = mutableListOf(), var customName: String = "", var isActive: Boolean = false) {
        fun isComplete() = sessionId.isNotEmpty() && exerciseId.isNotEmpty() && numExercise.isNotEmpty() && numFile.isNotEmpty()
        fun toJson() = JSONObject().apply { put("id", id); put("s_id", sessionId); put("e_id", exerciseId); put("n_e", numExercise); put("n_f", numFile); put("f_n", fileName); put("f_sz", fileSizeRaw); put("note", note); put("c_n", customName); put("is_a", isActive); val tArr = JSONArray(); timings.forEach { tArr.put(JSONObject().apply { put("t", it.time); put("m", it.max); put("s", it.step); put("mt", it.multType); put("mv", it.multVal); put("en", it.isEnabled) }) }; put("timings", tArr) }
        companion object {
            fun fromJson(j: JSONObject, context: Context): VideoItem {
                val vi = VideoItem(id = j.optString("id", generateId()), sessionId = j.optString("s_id"), exerciseId = j.optString("e_id"), numExercise = j.optString("n_e"), numFile = j.optString("n_f"), fileName = j.optString("f_n"), fileSizeRaw = j.optLong("f_sz"), note = j.optString("note"), customName = j.optString("c_n"), isActive = j.optBoolean("is_a", false))
                val tArr = j.optJSONArray("timings"); if (tArr != null) for (i in 0 until tArr.length()) { val tObj = tArr.getJSONObject(i); val time = tObj.getInt("t"); val currFromPrefs = getTimingCurr(context, vi.id, time); vi.timings.add(Timing(time, tObj.optLong("m", 0L), currFromPrefs, tObj.optLong("s", 0L), tObj.optInt("mt", 0), tObj.optInt("mv", 1), tObj.optBoolean("en", false))) }
                return vi
            }
        }
    }

    inner class VideoListAdapter : ListAdapter<VideoItem, VideoListAdapter.ViewHolder>(VideoItemDiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_mp4_file, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val indicator: View = v.findViewById(R.id.col_color_indicator); private val sN: TextView = v.findViewById(R.id.col_session_name); private val nE: TextView = v.findViewById(R.id.col_num_exercise); private val eN: TextView = v.findViewById(R.id.col_exercise_name); private val nF: TextView = v.findViewById(R.id.col_num_file); private val fN: TextView = v.findViewById(R.id.col_file_name); private val fS: TextView = v.findViewById(R.id.col_file_size); private val note: TextView = v.findViewById(R.id.col_note)
            fun bind(item: VideoItem) {
                indicator.setBackgroundColor(if (item.isActive) 0xFF99CC00.toInt() else 0xFFF44336.toInt())
                sN.text = sessionOptions.find { it.id == item.sessionId }?.name ?: ""; nE.text = item.numExercise; eN.text = exerciseOptions.find { it.id == item.exerciseId }?.name ?: ""; nF.text = item.numFile; fN.text = if (item.customName.isNotEmpty()) item.customName else item.fileName; note.text = item.note; fS.text = formatFileSize(item.fileSizeRaw)
                indicator.setOnClickListener { if (!item.isActive && !item.isComplete()) Toast.makeText(this@SettingsActivity, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show() else updateItemById(item.id) { it.copy(isActive = !it.isActive) } }
                sN.setOnClickListener { showOptionsDialog(getString(R.string.header_session), sessionOptions, item.id) { showAddSessionDialog(item.id) } }
                nE.setOnClickListener { if (item.sessionId.isEmpty()) Toast.makeText(this@SettingsActivity, getString(R.string.select_session_first), Toast.LENGTH_SHORT).show() else showExerciseNumPopup(item.id) }
                eN.setOnClickListener { showOptionsDialog(getString(R.string.header_exercise), exerciseOptions, item.id) { showAddExerciseDialog(item.id) } }
                nF.setOnClickListener { if (item.exerciseId.isEmpty()) Toast.makeText(this@SettingsActivity, getString(R.string.select_exercise_first), Toast.LENGTH_SHORT).show() else showFileNumPopup(item.id) }
                fN.setOnClickListener { val folder = getFolderDocumentFile(); val file = folder?.findFile(item.fileName); if (file != null) { val intent = Intent(this@SettingsActivity, VideoPlayerActivity::class.java); intent.putExtra("video_uri", file.uri); intent.putExtra("video_item_id", item.id); intent.putExtra("is_from_settings", true); startActivity(intent) } else Toast.makeText(this@SettingsActivity, getString(R.string.files_not_found), Toast.LENGTH_SHORT).show() }
                fN.setOnLongClickListener { showFileNameEditDialog(item.id); true }; note.setOnClickListener { showNoteEditDialog(item.id) }
            }
            private fun showOptionsDialog(title: String, options: MutableList<ConfigOption>, id: String, onAdd: () -> Unit) {
                val dialogView = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }; val listView = ListView(this@SettingsActivity); val displayOptions = mutableListOf<Any>(getString(R.string.empty_option)); displayOptions.addAll(options); listView.adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, displayOptions)
                listView.setOnItemClickListener { _, _, i, _ -> val selected = if (i == 0) null else displayOptions[i] as ConfigOption; updateItemById(id) { item -> val updated = if (title == getString(R.string.header_session)) item.copy(sessionId = selected?.id ?: "", numExercise = "") else item.copy(exerciseId = selected?.id ?: "", numFile = ""); if (!updated.isComplete()) updated.copy(isActive = false) else updated }; alertDialog?.dismiss() }
                listView.setOnItemLongClickListener { _, _, i, _ ->
                    if (i == 0) return@setOnItemLongClickListener true
                    val selected = displayOptions[i] as ConfigOption; val builder = AlertDialog.Builder(this@SettingsActivity).setTitle(if (title == getString(R.string.header_exercise)) getString(R.string.exercise_title_format, selected.name) else getString(R.string.delete_option_title))
                    if (title == getString(R.string.header_exercise)) {
                        val layout = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(40, 20, 40, 0); weightSum = 2f }; val leftBox = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }; leftBox.addView(TextView(this@SettingsActivity).apply { text = getString(R.string.label_position); textSize = 12f }); val leftValue = TextView(this@SettingsActivity).apply { text = getCategoryState(this@SettingsActivity, selected.id); textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal) }; leftValue.setOnClickListener { v -> val optionsList = (0..999).map { String.format(Locale.US, "%03d", it) }; val listPopup = ListPopupWindow(this@SettingsActivity); listPopup.setAdapter(ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, optionsList)); listPopup.anchorView = v; listPopup.width = (100 * resources.displayMetrics.density).toInt(); listPopup.setOnItemClickListener { _, _, pos, _ -> leftValue.text = optionsList[pos]; listPopup.dismiss() }; listPopup.show() }; leftBox.addView(leftValue); val rightBox = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(20, 0, 0, 0) }; rightBox.addView(TextView(this@SettingsActivity).apply { text = getString(R.string.label_reset_to); textSize = 12f }); val rightValue = TextView(this@SettingsActivity).apply { text = getResetState(this@SettingsActivity, selected.id); textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal) }; rightValue.setOnClickListener { v -> val optionsList = listOf("000", "001"); val listPopup = ListPopupWindow(this@SettingsActivity); listPopup.setAdapter(ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, optionsList)); listPopup.anchorView = v; listPopup.width = (100 * resources.displayMetrics.density).toInt(); listPopup.setOnItemClickListener { _, _, pos, _ -> rightValue.text = optionsList[pos]; listPopup.dismiss() }; listPopup.show() }; rightBox.addView(rightValue); layout.addView(leftBox); layout.addView(rightBox); builder.setView(layout)
                        builder.setNeutralButton(getString(R.string.delete)) { _, _ -> options.remove(selected); val folder = getFolderDocumentFile() ?: return@setNeutralButton; saveToConfig(folder, currentList.map { if (it.exerciseId == selected.id) it.copy(exerciseId = "", numFile = "", isActive = false) else it }); loadUIFromConfig() }
                        builder.setPositiveButton(getString(R.string.dialog_save)) { _, _ -> saveCategoryState(this@SettingsActivity, selected.id, leftValue.text.toString()); saveResetState(this@SettingsActivity, selected.id, rightValue.text.toString()); val folder = getFolderDocumentFile() ?: return@setPositiveButton; saveToConfig(folder, currentList); loadUIFromConfig() }
                    } else { builder.setMessage(selected.name).setNeutralButton(getString(R.string.delete)) { _, _ -> options.remove(selected); val folder = getFolderDocumentFile() ?: return@setNeutralButton; saveToConfig(folder, currentList.map { if (it.sessionId == selected.id) it.copy(sessionId = "", numExercise = "", isActive = false) else it }); loadUIFromConfig() } }
                    builder.setNegativeButton(getString(R.string.dialog_cancel), null); val dlg = builder.create(); dlg.setOnShowListener { tintDialogButtons(dlg, true) }; dlg.show(); true
                }
                dialogView.addView(listView); dialogView.addView(ImageButton(this@SettingsActivity).apply { setImageResource(android.R.drawable.ic_input_add); background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.btn_round_bg); layoutParams = LinearLayout.LayoutParams(50, 50).apply { gravity = Gravity.CENTER; topMargin = 16 }; setOnClickListener { onAdd(); alertDialog?.dismiss() } }); alertDialog = AlertDialog.Builder(this@SettingsActivity).setTitle(title).setView(dialogView).create(); alertDialog?.show(); val lp = WindowManager.LayoutParams(); lp.copyFrom(alertDialog?.window?.attributes); lp.width = Math.min((300 * resources.displayMetrics.density).toInt(), (resources.displayMetrics.widthPixels * 0.9).toInt()); alertDialog?.window?.attributes = lp
            }
            private var alertDialog: AlertDialog? = null
            private fun showExerciseNumPopup(id: String) { val item = currentList.find { it.id == id } ?: return; val used = currentList.filter { it.sessionId == item.sessionId && it.sessionId.isNotEmpty() && it.exerciseId != item.exerciseId }.map { it.numExercise }.toSet(); val options = mutableListOf(getString(R.string.empty_option)); options.addAll(generateFreeNumbers(used, 1, 99, "%02d")); val listPopup = ListPopupWindow(this@SettingsActivity); listPopup.setAdapter(ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, options)); listPopup.anchorView = nE; listPopup.width = (100 * resources.displayMetrics.density).toInt(); listPopup.isModal = true; listPopup.setOnItemClickListener { _, _, position, _ -> val selected = options[position]; updateItemById(id) { val updated = it.copy(numExercise = if (selected == getString(R.string.empty_option)) "" else selected); if (!updated.isComplete()) updated.copy(isActive = false) else updated }; listPopup.dismiss() }; listPopup.show() }
            private fun showFileNumPopup(id: String) { val item = currentList.find { it.id == id } ?: return; val used = currentList.filter { it.exerciseId == item.exerciseId && it.exerciseId.isNotEmpty() }.map { it.numFile }.toSet(); val options = mutableListOf(getString(R.string.empty_option)); options.addAll(generateFreeNumbers(used, 1, 999, "%03d")); val listPopup = ListPopupWindow(this@SettingsActivity); listPopup.setAdapter(ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, options)); listPopup.anchorView = nF; listPopup.width = (120 * resources.displayMetrics.density).toInt(); listPopup.isModal = true; listPopup.setOnItemClickListener { _, _, position, _ -> val selected = options[position]; updateItemById(id) { val updated = it.copy(numFile = if (selected == getString(R.string.empty_option)) "" else selected); if (!updated.isComplete()) updated.copy(isActive = false) else updated }; listPopup.dismiss() }; listPopup.show() }
            private fun showNoteEditDialog(id: String) { val item = currentList.find { it.id == id } ?: return; val input = EditText(this@SettingsActivity).apply { setText(item.note) }; val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.note_title)).setView(input).setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> updateItemById(id) { it.copy(note = input.text.toString()) } }.setNegativeButton(getString(R.string.dialog_cancel), null).create(); dialog.setOnShowListener { tintDialogButtons(dialog) }; dialog.show() }
            private fun showFileNameEditDialog(id: String) {
                val item = currentList.find { it.id == id } ?: return; val input = EditText(this@SettingsActivity).apply { setText(if (item.customName.isNotEmpty()) item.customName else item.fileName.substringBeforeLast(".")); hint = getString(R.string.name_hint); selectAll() }; val layout = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0); addView(input); addView(TextView(this@SettingsActivity).apply { text = item.fileName; textSize = 12f; alpha = 0.6f; setPadding(0, 8, 0, 0) }) }; val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.dialog_title_name)).setView(layout).setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> updateItemById(id) { it.copy(customName = input.text.toString().trim()) } }.setNegativeButton(getString(R.string.dialog_cancel), null).setNeutralButton(getString(R.string.delete)) { _, _ -> updateConfig { json -> val itemsArray = json.optJSONArray("video_items") ?: JSONArray(); val newItems = JSONArray(); for (i in 0 until itemsArray.length()) { val obj = itemsArray.getJSONObject(i); if (obj.optString("id") != id) newItems.put(obj) }; json.put("video_items", newItems) } }.create()
                dialog.setOnShowListener { tintDialogButtons(dialog, true) }; dialog.show()
            }
            private fun showAddSessionDialog(id: String) {
                val layout = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }; var selectedNum = ""; val numDisplay = TextView(this@SettingsActivity).apply { text = getString(R.string.session_no_not_selected); gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }; val numBtn = ImageButton(this@SettingsActivity).apply { setImageResource(android.R.drawable.ic_menu_sort_by_size); setColorFilter(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_green_dark), PorterDuff.Mode.SRC_IN); background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.btn_round_bg); layoutParams = LinearLayout.LayoutParams(50, 50).apply { gravity = Gravity.CENTER }; setOnClickListener { btn -> val used = sessionOptions.map { it.name.split(" ")[0] }.toSet(); val available = (1..9).map { it.toString() }.filter { !used.contains(it) }; val listPopup = ListPopupWindow(this@SettingsActivity); listPopup.setAdapter(ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, available)); listPopup.anchorView = btn; listPopup.width = (80 * resources.displayMetrics.density).toInt(); listPopup.setOnItemClickListener { _, _, pos, _ -> selectedNum = available[pos]; numDisplay.text = getString(R.string.session_number_selected, selectedNum); listPopup.dismiss() }; listPopup.show() } }; val nameInput = EditText(this@SettingsActivity).apply { hint = getString(R.string.name_hint) }; layout.addView(numBtn); layout.addView(numDisplay); layout.addView(nameInput); val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.new_session)).setView(layout).setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> val name = nameInput.text.toString().trim(); if (selectedNum.isEmpty()) { Toast.makeText(this@SettingsActivity, getString(R.string.select_number), Toast.LENGTH_SHORT).show(); return@setPositiveButton }; val combined = "$selectedNum $name"; val newOption = ConfigOption(generateId(), combined); sessionOptions.add(newOption); sessionOptions.sortWith(compareBy { extractNumber(it.name) }); updateItemById(id) { it.copy(sessionId = newOption.id) } }.setNegativeButton(getString(R.string.dialog_cancel), null).create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }; dialog.show()
            }
            private fun showAddExerciseDialog(id: String) { val input = EditText(this@SettingsActivity).apply { hint = getString(R.string.exercise_name_hint) }; val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.new_exercise)).setView(input).setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> val name = input.text.toString().trim(); if (name.isNotEmpty()) { val newOption = ConfigOption(generateId(), name); exerciseOptions.add(newOption); exerciseOptions.sortWith(compareBy { extractNumber(it.name) }); saveCategoryState(this@SettingsActivity, newOption.id, "000"); saveResetState(this@SettingsActivity, newOption.id, "001"); updateItemById(id) { it.copy(exerciseId = newOption.id) } } }.setNegativeButton(getString(R.string.dialog_cancel), null).create(); dialog.setOnShowListener { tintDialogButtons(dialog) }; dialog.show() }
        }
    }

    private fun getFolderDocumentFile(): DocumentFile? = getFolderUri()?.let { DocumentFile.fromTreeUri(this, it) }
    private fun readConfigJson(configFile: DocumentFile): JSONObject? = try { contentResolver.openInputStream(configFile.uri)?.use { inputStream -> JSONObject(inputStream.bufferedReader().readText()) } } catch (e: Exception) { Log.e(TAG, getString(R.string.error_reading), e); null }
    private fun tintDialogButtons(dialog: AlertDialog, neutralIsDestructive: Boolean = false) { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark)); dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark)); if (neutralIsDestructive) dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)) }
    private fun generateFreeNumbers(used: Set<String>, from: Int, to: Int, format: String): List<String> { val result = mutableListOf<String>(); for (i in from..to) { val num = String.format(Locale.US, format, i); if (!used.contains(num)) result.add(num) }; return result }
    private fun findConfigFileForRead(folder: DocumentFile): DocumentFile? { folder.findFile(CONFIG_FILE_NAME)?.let { return it }; return folder.listFiles().firstOrNull { (it.name ?: "").startsWith(CONFIG_FILE_NAME) } }
    private fun findOrCreateConfigFile(folder: DocumentFile): DocumentFile? = findConfigFileForRead(folder) ?: folder.createFile("application/json", CONFIG_FILE_NAME)
    private fun updateConfig(transformer: (JSONObject) -> Unit) { val folder = getFolderDocumentFile() ?: return; val configFile = findOrCreateConfigFile(folder) ?: return; val json = readConfigJson(configFile) ?: JSONObject(); transformer(json); contentResolver.openOutputStream(configFile.uri, "wt")?.use { writer -> OutputStreamWriter(writer).use { it.write(json.toString(4)) } }; loadUIFromConfig() }
}

class VideoItemDiffCallback : DiffUtil.ItemCallback<SettingsActivity.VideoItem>() { override fun areItemsTheSame(oldItem: SettingsActivity.VideoItem, newItem: SettingsActivity.VideoItem) = oldItem.id == newItem.id; override fun areContentsTheSame(oldItem: SettingsActivity.VideoItem, newItem: SettingsActivity.VideoItem) = oldItem == newItem }

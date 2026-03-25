package com.prim.texfit

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
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
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow
import kotlin.system.exitProcess

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val TAG = "SettingsActivity"
        private const val CONFIG_FILE_NAME = "texfit.cfg"
        private const val EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI"

        fun applyLaunchLogic(json: JSONObject): JSONObject {
            val sessionOptions = mutableListOf<String>()
            json.optJSONArray("session_options")?.let { arr ->
                for (i in 0 until arr.length()) sessionOptions.add(arr.getString(i))
            }
            val exerciseOptions = mutableListOf<String>()
            json.optJSONArray("exercise_options")?.let { arr ->
                for (i in 0 until arr.length()) exerciseOptions.add(arr.getString(i))
            }
            val categoryState = mutableMapOf<String, String>()
            json.optJSONObject("category_state")?.let { obj ->
                obj.keys().forEach { key -> categoryState[key] = obj.getString(key) }
            }
            val resetState = mutableMapOf<String, String>()
            json.optJSONObject("reset_state")?.let { obj ->
                obj.keys().forEach { key -> resetState[key] = obj.getString(key) }
            }
            val videoItemsArray = json.optJSONArray("video_items") ?: JSONArray()
            val allItems = mutableListOf<VideoItem>()
            for (i in 0 until videoItemsArray.length()) {
                allItems.add(VideoItem.fromJson(videoItemsArray.getJSONObject(i), sessionOptions, exerciseOptions))
            }

            val sourceTable = allItems.filter { it.isComplete() }
                .sortedWith(compareBy(
                    { it.sessionNum.toIntOrNull() ?: 0 },
                    { it.numExercise.toIntOrNull() ?: 0 },
                    { it.numFile.toIntOrNull() ?: 0 }
                ))

            val selectedItems = mutableListOf<VideoItem>()
            val changedExercises = mutableSetOf<String>()
            val slotGroups = sourceTable.groupBy { "${it.sessionNum}:${it.numExercise}" }

            for (group in slotGroups.values) {
                val firstRow = group.first()
                val exName = firstRow.exerciseName
                val currentState = categoryState[exName]?.toIntOrNull() ?: 0
                var nextStep = currentState + 1
                
                val exerciseFiles = sourceTable.filter { it.exerciseName == exName && it.numFile.isNotEmpty() }
                if (exerciseFiles.isEmpty()) continue
                val limit = exerciseFiles.maxOf { it.numFile.toIntOrNull() ?: 0 }
                val resetVal = resetState[exName]?.toIntOrNull() ?: 0
                if (nextStep > limit) nextStep = resetVal
                
                for (row in group) {
                    if (nextStep == (row.numFile.toIntOrNull() ?: 0)) {
                        selectedItems.add(row)
                        categoryState[exName] = String.format(Locale.US, "%03d", nextStep)
                        changedExercises.add(exName)
                        break 
                    }
                }
            }

            for (exName in categoryState.keys) {
                if (exName !in changedExercises) {
                    val currentState = categoryState[exName]?.toIntOrNull() ?: 0
                    val exerciseFiles = sourceTable.filter { it.exerciseName == exName && it.numFile.isNotEmpty() }
                    if (exerciseFiles.isNotEmpty()) {
                        val limit = exerciseFiles.maxOf { it.numFile.toIntOrNull() ?: 0 }
                        val resetVal = resetState[exName]?.toIntOrNull() ?: 0
                        var nextStep = currentState + 1
                        if (nextStep > limit) nextStep = resetVal
                        categoryState[exName] = String.format(Locale.US, "%03d", nextStep)
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
                        val newCurr = when {
                            t.curr == 0L -> t.step
                            t.curr == t.step && multiplier > 1 -> t.step * multiplier
                            else -> t.curr + (t.step * multiplier)
                        }
                        t.curr = newCurr.coerceAtMost(t.max)
                    }
                }
            }

            val newItemsArray = JSONArray()
            allItems.forEach { newItemsArray.put(it.toJson(sessionOptions, exerciseOptions)) }
            json.put("video_items", newItemsArray)
            
            val stateObj = JSONObject()
            categoryState.forEach { (k, v) -> stateObj.put(k, v) }
            json.put("category_state", stateObj)

            val titlesArray = JSONArray()
            selectedItems.forEach { selected ->
                val entry = JSONArray()
                entry.put(selected.id) 
                entry.put(0) // status
                entry.put(0) // last playback position (ms)
                titlesArray.put(entry)
            }
            json.put("titles", titlesArray)

            return json
        }
    }

    private lateinit var selectedFolderPathTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoListAdapter
    private lateinit var tvSetTime: TextView
    private lateinit var etTopInput: EditText
    private lateinit var btnLaunch: Button
    
    private lateinit var hColor: TextView
    private lateinit var hCat1: TextView
    private lateinit var hCat2: TextView
    private lateinit var hCat3: TextView
    private lateinit var hSize: TextView
    private lateinit var hNote: TextView

    private var sessionOptions = mutableListOf<String>()
    private var exerciseOptions = mutableListOf<String>()
    private var categoryState = mutableMapOf<String, String>()
    private var resetState = mutableMapOf<String, String>()
    private var activeExercisesOrder = mutableListOf<String>()
    
    private var lastAutoLaunchTs: Long = 0L

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, 
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                saveSelectedFolderUri(it)
                displaySelectedFolder(it)
                loadUIFromConfig()
            } catch (e: Exception) { Log.e(TAG, "Ошибка прав", e) }
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
    }) { uri -> uri?.let { addSingleFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }

        selectedFolderPathTextView = findViewById(R.id.selected_folder_path_text_view)
        recyclerView = findViewById(R.id.mp4_files_recycler_view)
        tvSetTime = findViewById(R.id.tv_set_time)
        etTopInput = findViewById(R.id.et_top_input)
        btnLaunch = findViewById(R.id.btn_launch)
        
        hColor = findViewById(R.id.header_color)
        hCat1 = findViewById(R.id.header_cat1)
        hCat2 = findViewById(R.id.header_cat2)
        hCat3 = findViewById(R.id.header_cat3)
        hSize = findViewById(R.id.header_size)
        hNote = findViewById(R.id.header_note)

        btnLaunch.setOnClickListener { 
            performLaunchStep()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoListAdapter()
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_add_folder).setOnClickListener { showAddMenu(it) }
        tvSetTime.setOnClickListener { showTimePicker() }

        loadAndDisplaySelectedFolder()
        loadUIFromConfig()
    }

    private fun performLaunchStep() {
        val folder = getFolderDocumentFile() ?: return
        val configFile = findConfigFileForRead(folder) ?: return
        val json = readConfigJson(configFile) ?: return
        
        applyLaunchLogic(json)
        
        contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
            OutputStreamWriter(outputStream).use { writer -> writer.write(json.toString(4)) }
        }

        loadUIFromConfig()
        
        val titles = json.optJSONArray("titles")
        if (titles == null || titles.length() == 0) {
            Toast.makeText(this, getString(R.string.no_active_exercises), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.files_found_count, titles.length()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return ""
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun updateTopInputUI() {
        val items = adapter.currentList
        val completeItems = items.filter { it.isComplete() }
        val currentActiveSet = completeItems.map { it.exerciseName }.toSet()

        activeExercisesOrder.removeAll { it !in currentActiveSet }
        currentActiveSet.forEach { if (it !in activeExercisesOrder) activeExercisesOrder.add(it) }

        if (activeExercisesOrder.isEmpty()) {
            etTopInput.setText("")
            return
        }

        val sb = StringBuilder("| ")
        activeExercisesOrder.forEach { ex ->
            val num = categoryState[ex] ?: "000"
            sb.append("$ex $num | ")
        }
        etTopInput.setText(sb.toString())
    }

    private fun showAddMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, getString(R.string.menu_folder)).setIcon(R.drawable.ic_add_folder)
        popup.menu.add(0, 2, 1, getString(R.string.menu_file)).setIcon(R.drawable.ic_add_video)
        popup.menu.add(0, 3, 2, getString(R.string.menu_refresh)).setIcon(R.drawable.ic_refresh_custom)
        popup.applyPopupForceShowIcon()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> selectFolderLauncher.launch(null)
                2 -> selectFileLauncher.launch(arrayOf("video/mp4"))
                3 -> performFullRefresh()
            }
            true
        }
        popup.show()
    }

    private fun addSingleFile(uri: Uri) {
        val folder = getFolderDocumentFile() ?: return
        try {
            val pickedFile = DocumentFile.fromSingleUri(this, uri) ?: return
            val name = pickedFile.name ?: "video.mp4"
            if (!name.endsWith(".mp4", ignoreCase = true)) {
                Toast.makeText(this, getString(R.string.file_not_mp4), Toast.LENGTH_SHORT).show()
                return
            }
            val currentItems = adapter.currentList.toMutableList()
            currentItems.add(VideoItem(id = UUID.randomUUID().toString(), fileName = name, fileSizeRaw = pickedFile.length()))
            saveToConfig(folder, currentItems)
            loadUIFromConfig()
        } catch (e: Exception) { Log.e(TAG, "Add file error", e) }
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            val timeStr = String.format(Locale.US, "%02d:%02d", h, m)
            tvSetTime.text = timeStr
            lastAutoLaunchTs = 0L
            getFolderDocumentFile()?.let { saveToConfig(it, adapter.currentList) }
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }

    private fun saveSelectedFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(SELECTED_FOLDER_URI_KEY, uri.toString())
        }
    }

    private fun loadAndDisplaySelectedFolder() { getFolderUri()?.let { displaySelectedFolder(it) } }

    private fun displaySelectedFolder(uri: Uri) {
        selectedFolderPathTextView.text = uri.path ?: getString(R.string.folder_selected)
    }

    private fun getFolderUri(): Uri? = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(SELECTED_FOLDER_URI_KEY, null)?.toUri()

    private fun loadUIFromConfig() {
        val folderUri = getFolderUri() ?: return
        val folder = DocumentFile.fromTreeUri(this, folderUri) ?: return
        val configFile = findConfigFileForRead(folder) ?: return
        
        try {
            val json = readConfigJson(configFile) ?: return
            
            if (json.has("button")) {
                val showButton = json.optInt("button", 0) == 1
                btnLaunch.visibility = if (showButton) View.VISIBLE else View.GONE
            } else {
                btnLaunch.visibility = View.GONE
            }
            
            val trainingTimeJson = json.opt("training_time")
            if (trainingTimeJson is JSONObject) {
                tvSetTime.text = trainingTimeJson.optString("value", getString(R.string.time_default))
                lastAutoLaunchTs = trainingTimeJson.optLong("last_auto_launch_ts", 0L)
            } else {
                tvSetTime.text = json.optString("training_time", getString(R.string.time_default))
                lastAutoLaunchTs = 0L
            }

            val headersJson = json.optJSONObject("headers")
            if (headersJson != null) {
                hColor.text = headersJson.optString("col", "Цвет"); hCat1.text = headersJson.optString("cat1", "Сеанс")
                hCat2.text = headersJson.optString("cat2", "Упражнение"); hCat3.text = headersJson.optString("cat3", "Название")
                hSize.text = headersJson.optString("size", "Размер"); hNote.text = headersJson.optString("note", "Прим.")
            }
            
            sessionOptions = mutableListOf()
            json.optJSONArray("session_options")?.let { arr ->
                for (i in 0 until arr.length()) sessionOptions.add(arr.getString(i))
            }
            exerciseOptions = mutableListOf()
            json.optJSONArray("exercise_options")?.let { arr ->
                for (i in 0 until arr.length()) exerciseOptions.add(arr.getString(i))
            }

            categoryState = mutableMapOf()
            json.optJSONObject("category_state")?.let { obj ->
                obj.keys().forEach { key -> categoryState[key] = obj.getString(key) }
            }
            
            resetState = mutableMapOf()
            json.optJSONObject("reset_state")?.let { obj ->
                obj.keys().forEach { key -> resetState[key] = obj.getString(key) }
            }

            exerciseOptions.forEach { ex ->
                if (!categoryState.containsKey(ex)) categoryState[ex] = "000"
                if (!resetState.containsKey(ex)) resetState[ex] = "000"
            }

            val array = json.optJSONArray("video_items") ?: JSONArray()
            val items = mutableListOf<VideoItem>()
            for (i in 0 until array.length()) {
                items.add(VideoItem.fromJson(array.getJSONObject(i), sessionOptions, exerciseOptions))
            }
            adapter.submitList(items)
            updateTopInputUI()
            
        } catch (e: Exception) { Log.e(TAG, "Ошибка загрузки", e) }
    }

    private fun performFullRefresh() {
        val folder = getFolderDocumentFile() ?: return
        val currentItems = adapter.currentList
        val filesInFolder = folder.listFiles().filter { it.name?.endsWith(".mp4", ignoreCase = true) == true }
        val fileNamesInFolder = filesInFolder.map { it.name ?: "" }.toSet()
        val updatedItems = currentItems.filter { it.fileName in fileNamesInFolder }.toMutableList()
        val existingNames = updatedItems.map { it.fileName }.toSet()
        filesInFolder.filter { (it.name ?: "") !in existingNames }.forEach { file ->
            updatedItems.add(VideoItem(id = UUID.randomUUID().toString(), fileName = file.name ?: "", fileSizeRaw = file.length()))
        }

        val sortedItems = updatedItems.sortedWith(compareByDescending<VideoItem> { it.isComplete() }
            .thenBy { it.sessionNum.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.numExercise.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.numFile.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.fileName }
        )

        saveToConfig(folder, sortedItems)
        loadUIFromConfig()
        Toast.makeText(this, getString(R.string.updated), Toast.LENGTH_SHORT).show()
    }

    private fun saveToConfig(folder: DocumentFile, items: List<VideoItem>, selectedItems: List<VideoItem>? = null) {
        try {
            val configFile = findOrCreateConfigFile(folder) ?: return
            val json = readConfigJson(configFile) ?: JSONObject()
            
            if (!json.has("button")) {
                json.put("button", 0)
            }
            
            val array = JSONArray()
            items.forEach { array.put(it.toJson(sessionOptions, exerciseOptions)) }
            json.put("video_items", array)
            json.put("session_options", JSONArray(sessionOptions))
            json.put("exercise_options", JSONArray(exerciseOptions))
            
            val stateObj = JSONObject()
            categoryState.forEach { (k, v) -> stateObj.put(k, v) }
            json.put("category_state", stateObj)
            
            val resetObj = JSONObject()
            resetState.forEach { (k, v) -> resetState[k] = v; resetObj.put(k, v) }
            json.put("reset_state", resetObj)

            val trainingTimeObj = JSONObject().apply {
                put("value", tvSetTime.text.toString())
                put("last_auto_launch_ts", lastAutoLaunchTs)
            }
            json.put("training_time", trainingTimeObj)
            
            json.put("folder_path", selectedFolderPathTextView.text.toString())
            
            val hObj = JSONObject().apply {
                put("col", hColor.text.toString()); put("cat1", hCat1.text.toString())
                put("cat2", hCat2.text.toString()); put("cat3", hCat3.text.toString())
                put("size", hSize.text.toString()); put("note", hNote.text.toString())
            }
            json.put("headers", hObj)
            
            if (selectedItems != null) {
                val titlesArray = JSONArray()
                selectedItems.forEach { selected ->
                    val entry = JSONArray()
                    entry.put(selected.id) 
                    entry.put(0) // status
                    entry.put(0) // last playback position (ms)
                    titlesArray.put(entry)
                }
                json.put("titles", titlesArray)
            }
            
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer -> writer.write(json.toString(4)) }
            }
        } catch (e: Exception) { Log.e(TAG, "Ошибка сохранения", e) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_exit -> { finishAffinity(); exitProcess(0) }
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    data class Timing(
        val time: Int, 
        var max: Long = 0L, 
        var curr: Long = 0L, 
        var step: Long = 0L, 
        var multType: Int = 0,
        var multVal: Int = 1,
        var isEnabled: Boolean = false
    )

    data class VideoItem(
        var id: String = UUID.randomUUID().toString(),
        var sessionNum: String = "",
        var sessionName: String = "",
        var numExercise: String = "", var exerciseName: String = "",
        var numFile: String = "", var fileName: String = "",
        var fileSizeRaw: Long = 0, var note: String = "",
        var timings: MutableList<Timing> = mutableListOf(),
        var customName: String = ""
    ) {
        fun isComplete() = sessionNum.isNotEmpty() && exerciseName.isNotEmpty() && numExercise.isNotEmpty() && numFile.isNotEmpty()
        
        fun toJson(sOpt: List<String>, eOpt: List<String>) = JSONObject().apply {
            put("id", id)
            val fullS = if (sessionNum.isNotEmpty()) "$sessionNum $sessionName" else ""
            put("s_idx", sOpt.indexOf(fullS))
            put("e_idx", eOpt.indexOf(exerciseName))
            put("n_e", numExercise); put("n_f", numFile)
            put("f_n", fileName); put("f_sz", fileSizeRaw); put("note", note)
            put("c_n", customName)
            
            val tArr = JSONArray()
            timings.forEach { 
                val tObj = JSONObject()
                tObj.put("t", it.time); tObj.put("m", it.max); tObj.put("c", it.curr)
                tObj.put("s", it.step); tObj.put("mt", it.multType); tObj.put("mv", it.multVal)
                tObj.put("en", it.isEnabled)
                tArr.put(tObj)
            }
            put("timings", tArr)
        }
        
        companion object {
            fun fromJson(j: JSONObject, sOpt: List<String>, eOpt: List<String>): VideoItem {
                val sIdx = j.optInt("s_idx", -1)
                val eIdx = j.optInt("e_idx", -1)
                val fullSession = if (sIdx in sOpt.indices) sOpt[sIdx] else ""
                val exName = if (eIdx in eOpt.indices) eOpt[eIdx] else ""
                
                val vi = VideoItem(
                    id = j.optString("id", UUID.randomUUID().toString()),
                    sessionNum = fullSession.substringBefore(" ", ""),
                    sessionName = fullSession.substringAfter(" ", ""),
                    numExercise = j.optString("n_e"),
                    exerciseName = exName,
                    numFile = j.optString("n_f"),
                    fileName = j.optString("f_n"),
                    fileSizeRaw = j.optLong("f_sz"),
                    note = j.optString("note"),
                    customName = j.optString("c_n")
                )
                
                val tArr = j.optJSONArray("timings")
                if (tArr != null) {
                    for (i in 0 until tArr.length()) {
                        val tObj = tArr.getJSONObject(i)
                        vi.timings.add(Timing(
                            tObj.getInt("t"), 
                            tObj.optLong("m", 0L), 
                            tObj.optLong("c", 0L),
                            tObj.optLong("s", 0L),
                            tObj.optInt("mt", 0),
                            tObj.optInt("mv", 1),
                            tObj.optBoolean("en", false)
                        ))
                    }
                }
                return vi
            }
        }
    }

    inner class VideoListAdapter : ListAdapter<VideoItem, VideoListAdapter.ViewHolder>(VideoItemDiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_mp4_file, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), position)

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val indicator: View = v.findViewById(R.id.col_color_indicator)
            private val sN: TextView = v.findViewById(R.id.col_session_name)
            private val nE: TextView = v.findViewById(R.id.col_num_exercise)
            private val eN: TextView = v.findViewById(R.id.col_exercise_name)
            private val nF: TextView = v.findViewById(R.id.col_num_file)
            private val fN: TextView = v.findViewById(R.id.col_file_name)
            private val fS: TextView = v.findViewById(R.id.col_file_size)
            private val note: TextView = v.findViewById(R.id.col_note)

            fun bind(item: VideoItem, pos: Int) {
                indicator.setBackgroundColor(if (item.isComplete()) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
                sN.text = if (item.sessionNum.isNotEmpty()) "${item.sessionNum} ${item.sessionName}" else ""
                nE.text = item.numExercise; eN.text = item.exerciseName
                nF.text = item.numFile
                fN.text = if (item.customName.isNotEmpty()) item.customName else item.fileName
                note.text = item.note
                fS.text = formatFileSize(item.fileSizeRaw)
                sN.setOnClickListener { showOptionsDialog("Сеанс", sessionOptions, pos) { showAddSessionDialog(pos) } }
                nE.setOnClickListener { 
                    if (getItem(pos).sessionNum.isEmpty()) Toast.makeText(this@SettingsActivity, getString(R.string.select_session_first), Toast.LENGTH_SHORT).show() 
                    else showExerciseNumPopup(pos) 
                }
                eN.setOnClickListener { showOptionsDialog("Упражнение", exerciseOptions, pos) { showAddExerciseDialog(pos) } }
                nF.setOnClickListener { 
                    if (getItem(pos).exerciseName.isEmpty()) Toast.makeText(this@SettingsActivity, getString(R.string.select_exercise_first), Toast.LENGTH_SHORT).show() 
                    else showFileNumPopup(pos) 
                }
                fN.setOnClickListener {
                    val currentItem = getItem(pos)
                    val folder = getFolderDocumentFile()
                    val file = folder?.findFile(currentItem.fileName)
                    if (file != null) {
                        val intent = Intent(this@SettingsActivity, VideoPlayerActivity::class.java)
                        intent.putExtra("video_uri", file.uri)
                        intent.putExtra("video_item_id", currentItem.id)
                        intent.putExtra("is_from_settings", true)
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@SettingsActivity, getString(R.string.files_not_found), Toast.LENGTH_SHORT).show()
                    }
                }
                fN.setOnLongClickListener { 
                    showFileNameEditDialog(pos)
                    true
                }
                note.setOnClickListener { showNoteEditDialog(pos) }
            }

            private fun showOptionsDialog(title: String, options: MutableList<String>, pos: Int, onAdd: () -> Unit) {
                val dialogView = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                val listView = ListView(this@SettingsActivity)
                val displayOptions = mutableListOf(getString(R.string.empty_option)); displayOptions.addAll(options)
                listView.adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, displayOptions)
                listView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                listView.setOnItemClickListener { _, _, i, _ ->
                    val value = if (i == 0) "" else displayOptions[i]
                    val item = getItem(pos)
                    if (title == "Сеанс") {
                        val sNumStr = value.substringBefore(" ", "")
                        val sName = value.substringAfter(" ", "")
                        updateItem(pos, item.copy(sessionNum = sNumStr, sessionName = sName, numExercise = ""))
                    } else { 
                        updateItem(pos, item.copy(exerciseName = value, numFile = ""))
                        if (value.isNotEmpty() && !categoryState.containsKey(value)) { categoryState[value] = "000"; resetState[value] = "000" } 
                    }
                    updateTopInputUI(); alertDialog?.dismiss()
                }
                listView.setOnItemLongClickListener { _, _, i, _ ->
                    if (i == 0) return@setOnItemLongClickListener true
                    val value = displayOptions[i]
                    val builder = AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(if (title == "Упражнение") getString(R.string.exercise_title_format, value) else getString(R.string.delete_option_title))
                    if (title == "Упражнение") {
                        val layout = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(40, 20, 40, 0); weightSum = 2f }
                        val leftBox = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                        leftBox.addView(TextView(this@SettingsActivity).apply { text = "Позиция:"; textSize = 12f })
                        val leftValue = TextView(this@SettingsActivity).apply { text = categoryState[value] ?: "000"; textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal) }
                        leftValue.setOnClickListener { v ->
                            val pop = PopupMenu(this@SettingsActivity, v)
                            for (j in 0..999) pop.menu.add(String.format(Locale.US, "%03d", j))
                            pop.setOnMenuItemClickListener { leftValue.text = it.title; true }; pop.show()
                        }
                        leftBox.addView(leftValue)
                        val rightBox = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(20, 0, 0, 0) }
                        rightBox.addView(TextView(this@SettingsActivity).apply { text = "Сброс на:"; textSize = 12f })
                        val rightValue = TextView(this@SettingsActivity).apply { text = resetState[value] ?: "000"; textSize = 18f; gravity = Gravity.CENTER; setBackgroundResource(android.R.drawable.editbox_background_normal) }
                        rightValue.setOnClickListener { v ->
                            val pop = PopupMenu(this@SettingsActivity, v)
                            pop.menu.add("000"); pop.menu.add("001")
                            pop.setOnMenuItemClickListener { rightValue.text = it.title; true }; pop.show()
                        }
                        rightBox.addView(rightValue); layout.addView(leftBox); layout.addView(rightBox)
                        builder.setView(layout)
                        builder.setNeutralButton("Удалить") { _, _ ->
                            options.remove(value); categoryState.remove(value); resetState.remove(value)
                            saveAndRefresh(currentList.map { if (it.exerciseName == value) it.copy(exerciseName = "", numFile = "") else it })
                        }
                        builder.setPositiveButton("Сохранить") { _, _ ->
                            categoryState[value] = leftValue.text.toString(); resetState[value] = rightValue.text.toString()
                            saveAndRefresh(currentList)
                        }
                    } else {
                        builder.setMessage(value).setPositiveButton("Удалить") { _, _ ->
                            options.remove(value)
                            val sNum = value.substringBefore(" ", "")
                            val sName = value.substringAfter(" ", "")
                            saveAndRefresh(currentList.map { if (it.sessionNum == sNum && it.sessionName == sName) it.copy(sessionNum = "", sessionName = "", numExercise = "") else it })
                        }
                    }
                    builder.setNegativeButton("Отмена", null); val dlg = builder.create()
                    dlg.setOnShowListener { tintDialogButtons(dlg, title == "Упражнение") }; dlg.show(); true
                }
                dialogView.addView(listView)
                val btnAdd = ImageButton(this@SettingsActivity).apply { 
                    setImageResource(android.R.drawable.ic_input_add); background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.btn_round_bg)
                    layoutParams = LinearLayout.LayoutParams(50, 50).apply { gravity = Gravity.CENTER; topMargin = 16 }
                    setOnClickListener { onAdd(); alertDialog?.dismiss() } 
                }
                dialogView.addView(btnAdd)
                alertDialog = AlertDialog.Builder(this@SettingsActivity).setTitle(title).setView(dialogView).create(); alertDialog?.show()
                val lp = WindowManager.LayoutParams(); lp.copyFrom(alertDialog?.window?.attributes)
                lp.width = Math.min((30 * resources.displayMetrics.scaledDensity * 10).toInt(), (resources.displayMetrics.widthPixels * 0.9).toInt())
                alertDialog?.window?.attributes = lp
            }
            private var alertDialog: AlertDialog? = null
            private fun saveAndRefresh(newList: List<VideoItem>) {
                val folder = getFolderDocumentFile() ?: return
                saveToConfig(folder, newList); loadUIFromConfig()
            }
            private fun showExerciseNumPopup(pos: Int) {
                val popup = PopupMenu(this@SettingsActivity, nE); popup.menu.add(getString(R.string.empty_option))
                val currentItem = getItem(pos)
                val used = currentList.filter { it.sessionNum == currentItem.sessionNum && it.sessionName == currentItem.sessionName && it.sessionNum.isNotEmpty() && it.exerciseName != currentItem.exerciseName }.map { it.numExercise }.toSet()
                generateFreeNumbers(used, 1, 99, "%02d").forEach { popup.menu.add(it) }
                popup.setOnMenuItemClickListener { updateItem(pos, getItem(pos).copy(numExercise = if (it.title == getString(R.string.empty_option)) "" else it.title.toString())); updateTopInputUI(); true }
                popup.applyPopupMinWidth(80); popup.show()
            }
            private fun showFileNumPopup(pos: Int) {
                val popup = PopupMenu(this@SettingsActivity, nF); popup.menu.add(getString(R.string.empty_option))
                val used = currentList.filter { it.exerciseName == getItem(pos).exerciseName && it.exerciseName.isNotEmpty() }.map { it.numFile }.toSet()
                generateFreeNumbers(used, 1, 999, "%03d").forEach { popup.menu.add(it) }
                popup.setOnMenuItemClickListener { updateItem(pos, getItem(pos).copy(numFile = if (it.title == getString(R.string.empty_option)) "" else it.title.toString())); updateTopInputUI(); true }
                popup.applyPopupMinWidth(120); popup.show()
            }
            private fun showNoteEditDialog(pos: Int) {
                val input = EditText(this@SettingsActivity).apply { setText(getItem(pos).note) }
                val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.note_title)).setView(input)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> updateItem(pos, getItem(pos).copy(note = input.text.toString())) }
                    .setNegativeButton(getString(R.string.dialog_cancel), null).create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }; dialog.show()
            }
            private fun showFileNameEditDialog(pos: Int) {
                val item = getItem(pos)
                val baseName = item.fileName.substringBeforeLast(".")
                val initialText = if (item.customName.isNotEmpty()) item.customName else baseName
                
                val layout = LinearLayout(this@SettingsActivity).apply { 
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 20, 40, 0) 
                }
                
                val input = EditText(this@SettingsActivity).apply { 
                    setText(initialText)
                    hint = "Введите название"
                }
                
                val fileNameLabel = TextView(this@SettingsActivity).apply {
                    text = item.fileName
                    textSize = 12f
                    alpha = 0.6f
                    setPadding(0, 8, 0, 0)
                }
                
                layout.addView(input)
                layout.addView(fileNameLabel)
                
                val dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Название")
                    .setView(layout)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> 
                        updateItem(pos, getItem(pos).copy(customName = input.text.toString().trim())) 
                    }
                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                    .create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }
                dialog.show()
            }
            private fun showAddSessionDialog(pos: Int) {
                val layout = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
                var selectedNum = ""
                val numBtn = ImageButton(this@SettingsActivity).apply { 
                    setImageResource(android.R.drawable.ic_menu_sort_by_size); setColorFilter(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_green_dark), PorterDuff.Mode.SRC_IN)
                    background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.btn_round_bg); layoutParams = LinearLayout.LayoutParams(50, 50).apply { gravity = Gravity.CENTER } 
                }
                val numDisplay = TextView(this@SettingsActivity).apply { text = "№ не выбран"; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
                numBtn.setOnClickListener { btn ->
                    val pop = PopupMenu(this@SettingsActivity, btn); val used = sessionOptions.map { it.split(" ")[0] }.toSet()
                    for (i in 1..9) { val n = i.toString(); if (!used.contains(n)) pop.menu.add(n) }
                    pop.setOnMenuItemClickListener { selectedNum = it.title.toString(); numDisplay.text = "Выбран № $selectedNum"; true }; pop.show()
                }
                val nameInput = EditText(this@SettingsActivity).apply { hint = getString(R.string.name_hint) }
                layout.addView(numBtn); layout.addView(numDisplay); layout.addView(nameInput)
                val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.new_session)).setView(layout)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                        val name = nameInput.text.toString().trim()
                        if (selectedNum.isEmpty()) { Toast.makeText(this@SettingsActivity, getString(R.string.select_number), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                        val combined = "$selectedNum $name"
                        if (!sessionOptions.contains(combined)) { sessionOptions.add(combined); sessionOptions.sort() }
                        updateItem(pos, getItem(pos).copy(sessionNum = selectedNum, sessionName = name))
                    }.setNegativeButton(getString(R.string.dialog_cancel), null).create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }; dialog.show()
            }
            private fun showAddExerciseDialog(pos: Int) {
                val input = EditText(this@SettingsActivity).apply { hint = getString(R.string.exercise_name_hint) }
                val dialog = AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.new_exercise)).setView(input)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            if (!exerciseOptions.contains(name)) { exerciseOptions.add(name); exerciseOptions.sort(); categoryState[name] = "000"; resetState[name] = "000" }
                            updateItem(pos, getItem(pos).copy(exerciseName = name))
                        }
                    }.setNegativeButton(getString(R.string.dialog_cancel), null).create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }; dialog.show()
            }
            private fun updateItem(pos: Int, newItem: VideoItem) {
                val newList = currentList.toMutableList(); newList[pos] = newItem
                val folder = getFolderDocumentFile() ?: return
                saveToConfig(folder, newList); loadUIFromConfig()
            }
        }
    }
    private fun getFolderDocumentFile(): DocumentFile? = getFolderUri()?.let { DocumentFile.fromTreeUri(this, it) }
    private fun readConfigJson(configFile: DocumentFile): JSONObject? = try {
        contentResolver.openInputStream(configFile.uri)?.use { inputStream -> JSONObject(inputStream.bufferedReader().readText()) }
    } catch (e: Exception) { Log.e(TAG, "Ошибка чтения", e); null }
    private fun tintDialogButtons(dialog: AlertDialog, neutralIsDestructive: Boolean = false) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        if (neutralIsDestructive) dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.RED)
    }
    private fun generateFreeNumbers(used: Set<String>, from: Int, to: Int, format: String): List<String> {
        val result = mutableListOf<String>()
        for (i in from..to) {
            val num = String.format(Locale.US, format, i)
            if (!used.contains(num)) result.add(num)
        }
        return result
    }
    private fun findConfigFileForRead(folder: DocumentFile): DocumentFile? {
        folder.findFile(CONFIG_FILE_NAME)?.let { return it }
        return folder.listFiles().firstOrNull { (it.name ?: "").startsWith(CONFIG_FILE_NAME) }
    }
    private fun findOrCreateConfigFile(folder: DocumentFile): DocumentFile? = findConfigFileForRead(folder) ?: folder.createFile("application/json", CONFIG_FILE_NAME)
}

private fun PopupMenu.applyPopupMinWidth(widthPx: Int) {
    try {
        val mPopupField = this.javaClass.getDeclaredField("mPopup").apply { isAccessible = true }
        val menuPopupHelper = mPopupField.get(this) ?: return
        menuPopupHelper.javaClass.getDeclaredMethod("setMinWidth", Int::class.java).invoke(menuPopupHelper, widthPx)
    } catch (e: Exception) { Log.e("SettingsActivity", "Popup width error", e) }
}
private fun PopupMenu.applyPopupForceShowIcon() {
    try {
        val mPopupField = this.javaClass.getDeclaredField("mPopup").apply { isAccessible = true }
        val menuPopupHelper = mPopupField.get(this) ?: return
        menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(menuPopupHelper, true)
    } catch (e: Exception) { Log.e("SettingsActivity", "Popup icon error", e) }
}
class VideoItemDiffCallback : DiffUtil.ItemCallback<SettingsActivity.VideoItem>() {
    override fun areItemsTheSame(oldItem: SettingsActivity.VideoItem, newItem: SettingsActivity.VideoItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: SettingsActivity.VideoItem, newItem: SettingsActivity.VideoItem) = oldItem == newItem
}

package com.prim.texfit

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
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
import kotlin.math.log10
import kotlin.math.pow
import kotlin.system.exitProcess

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "TexfitPrefs"
        private const val SELECTED_FOLDER_URI_KEY = "selectedFolderUri"
        private const val SELECTED_TIME_KEY = "selectedTime"
        private const val TAG = "SettingsActivity"
        private const val CONFIG_FILE_NAME = "texfit.cfg"
    }

    private lateinit var selectedFolderPathTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoListAdapter
    private lateinit var tvSetTime: TextView
    private lateinit var etTopInput: EditText
    
    private lateinit var hColor: TextView
    private lateinit var hCat1: TextView
    private lateinit var hCat2: TextView
    private lateinit var hCat3: TextView
    private lateinit var hSize: TextView
    private lateinit var hNote: TextView

    private var sessionOptions = mutableListOf<String>()
    private var exerciseOptions = mutableListOf<String>()
    private var categoryState = mutableMapOf<String, String>()
    private var activeExercisesOrder = mutableListOf<String>()

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
                getFolderUri()?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
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
        
        hColor = findViewById(R.id.header_color)
        hCat1 = findViewById(R.id.header_cat1)
        hCat2 = findViewById(R.id.header_cat2)
        hCat3 = findViewById(R.id.header_cat3)
        hSize = findViewById(R.id.header_size)
        hNote = findViewById(R.id.header_note)

        findViewById<View>(R.id.btn_launch).setOnClickListener { 
            performLaunchStep()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoListAdapter()
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_add_folder).setOnClickListener { showAddMenu(it) }
        tvSetTime.setOnClickListener { showTimePicker() }

        loadAndDisplaySelectedFolder()
        loadSelectedTime()
        loadUIFromConfig()
    }

    private fun performLaunchStep() {
        val items = adapter.currentList
        val activeItems = items.filter { it.isComplete() }
        if (activeItems.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_active_exercises), Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Сортировка: Сеанс (№) -> Упражнение (№)
        val sortedItems = activeItems.sortedWith(compareBy({
            it.sessionName.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 999
        }, {
            it.numExercise.toIntOrNull() ?: 999
        }))

        // 2. Оптимизация: создаем мапу для быстрого поиска файлов по (упражнение, номер)
        val fileMap = items.groupBy { it.exerciseName to it.numFile }
            .mapValues { entry -> entry.value.map { it to items.indexOf(it) } }

        // 3. Каскадный поиск файлов n+1
        val newTitlesIndices = mutableListOf<Int>()
        var foundCount = 0
        
        for (item in sortedItems) {
            val currentNum = categoryState[item.exerciseName]?.toIntOrNull() ?: 0
            val nextNum = currentNum + 1
            val nextNumStr = String.format(Locale.US, "%03d", nextNum)

            // Быстрый поиск через мапу
            val key = item.exerciseName to nextNumStr
            if (fileMap.containsKey(key)) {
                // Берем первый найденный файл
                val (foundItem, foundIndex) = fileMap[key]!!.first()
                newTitlesIndices.add(foundIndex)
                foundCount++
            }

            // Всегда обновляем categoryState на n+1
            categoryState[item.exerciseName] = nextNumStr
        }

        // 4. Сохраняем один раз
        if (newTitlesIndices.isNotEmpty()) {
            val folder = getFolderDocumentFile() ?: return
            updateTitlesAndSave(folder, newTitlesIndices, items)
            Toast.makeText(this, getString(R.string.files_found_count, foundCount), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.files_not_found), Toast.LENGTH_SHORT).show()
        }

        // 5. Обновляем UI
        updateTopInputUI()
    }

    private fun updateTitlesAndSave(folder: DocumentFile, newTitlesIndices: List<Int>, items: List<VideoItem>) {
        val configFile = findConfigFileForRead(folder) ?: return

        try {
            // Читаем существующий JSON или создаем новый
            val json = readConfigJson(configFile) ?: JSONObject()

            // Обновляем titles - заменяем весь массив
            val titlesArray = JSONArray()
            newTitlesIndices.forEach { index -> titlesArray.put(index) }
            json.put("titles", titlesArray)

            // Обновляем video_items
            val videoArray = JSONArray()
            items.forEach { videoArray.put(it.toJson(sessionOptions, exerciseOptions)) }
            json.put("video_items", videoArray)
            
            // Обновляем category_state
            val stateObj = JSONObject()
            categoryState.forEach { (k, v) -> stateObj.put(k, v) }
            json.put("category_state", stateObj)

            // Сохраняем с компактным форматированием (2 пробела вместо 4)
            contentResolver.openOutputStream(configFile.uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer -> writer.write(json.toString(2)) }
            }
        } catch (e: Exception) { Log.e(TAG, "Update titles error", e) }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return ""
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun updateTopInputUI() {
        val items = adapter.currentList
        val currentActiveSet = items.filter { it.isComplete() }.map { it.exerciseName }.toSet()

        categoryState.keys.forEach { key ->
            if (key !in currentActiveSet) {
                categoryState[key] = "000"
            }
        }

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
            currentItems.add(VideoItem(fileName = name, fileSizeRaw = pickedFile.length()))
            saveToConfig(folder, currentItems)
            loadUIFromConfig()
        } catch (e: Exception) { Log.e(TAG, "Add file error", e) }
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            val timeStr = String.format(Locale.US, "%02d:%02d", h, m)
            tvSetTime.text = timeStr
            saveSelectedTime(timeStr)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }

    private fun saveSelectedFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(SELECTED_FOLDER_URI_KEY, uri.toString())
        }
    }

    private fun saveSelectedTime(time: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(SELECTED_TIME_KEY, time)
        }
    }

    private fun loadSelectedTime() {
        tvSetTime.text = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SELECTED_TIME_KEY, getString(R.string.time_default))
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
        
        val configFile = findConfigFileForRead(folder)
        if (configFile == null) {
            Log.d(TAG, "loadUIFromConfig: Config not found in $folderUri")
            return
        }
        
        try {
            val json = readConfigJson(configFile) ?: return
            val headersJson = json.optJSONObject("headers")
                if (headersJson != null) {
                    hColor.text = headersJson.optString("col", "Цвет"); hCat1.text = headersJson.optString("cat1", "Сеанс")
                    hCat2.text = headersJson.optString("cat2", "Упражнение"); hCat3.text = headersJson.optString("cat3", "Файлы")
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
            val stateObj = json.optJSONObject("category_state")
            stateObj?.keys()?.forEach { key ->
                categoryState[key] = stateObj.getString(key)
            }
            exerciseOptions.forEach { ex ->
                if (!categoryState.containsKey(ex)) categoryState[ex] = "000"
            }

            val array = json.optJSONArray("video_items") ?: JSONArray()
            val items = mutableListOf<VideoItem>()
            for (i in 0 until array.length()) {
                items.add(VideoItem.fromJson(array.getJSONObject(i), sessionOptions, exerciseOptions))
            }
            adapter.submitList(items)
            updateTopInputUI()
        } catch (e: Exception) { 
            Log.e(TAG, "Ошибка загрузки конфигурации", e)
            Toast.makeText(this, getString(R.string.error_read_settings), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performFullRefresh() {
        val folder = getFolderDocumentFile() ?: return
        val currentItems = adapter.currentList
        val filesInFolder = folder.listFiles().filter { it.name?.endsWith(".mp4", ignoreCase = true) == true }
        val fileNamesInFolder = filesInFolder.map { it.name ?: "" }.toSet()
        val updatedItems = currentItems.filter { it.fileName in fileNamesInFolder }.toMutableList()
        val existingNames = updatedItems.map { it.fileName }.toSet()
        filesInFolder.filter { (it.name ?: "") !in existingNames }.forEach { file ->
            updatedItems.add(VideoItem(fileName = file.name ?: "", fileSizeRaw = file.length()))
        }
        saveToConfig(folder, updatedItems)
        loadUIFromConfig()
        Toast.makeText(this, getString(R.string.updated), Toast.LENGTH_SHORT).show()
    }

    private fun saveToConfig(folder: DocumentFile, items: List<VideoItem>) {
        try {
            val configFile = findOrCreateConfigFile(folder)
            
            configFile?.let { file ->
                val json = JSONObject().apply {
                    val array = JSONArray()
                    items.forEach { array.put(it.toJson(sessionOptions, exerciseOptions)) }
                    put("video_items", array)
                    put("session_options", JSONArray(sessionOptions))
                    put("exercise_options", JSONArray(exerciseOptions))
                    val stateObj = JSONObject()
                    categoryState.forEach { (k, v) -> stateObj.put(k, v) }
                    put("category_state", stateObj)
                    put("training_time", tvSetTime.text.toString())
                    put("folder_path", selectedFolderPathTextView.text.toString())
                    val hObj = JSONObject().apply {
                        put("col", hColor.text.toString()); put("cat1", hCat1.text.toString())
                        put("cat2", hCat2.text.toString()); put("cat3", hCat3.text.toString())
                        put("size", hSize.text.toString()); put("note", hNote.text.toString())
                    }
                    put("headers", hObj)
                }
                contentResolver.openOutputStream(file.uri, "wt")?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer -> writer.write(json.toString(4)) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Ошибка сохранения конфигурации", e) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_exit -> { 
                finishAffinity()
                exitProcess(0)
            }
            android.R.id.home -> { 
                onBackPressedDispatcher.onBackPressed()
                true 
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    data class VideoItem(
        var sessionName: String = "",
        var numExercise: String = "", var exerciseName: String = "",
        var numFile: String = "", var fileName: String = "",
        var fileSizeRaw: Long = 0, var note: String = ""
    ) {
        fun isComplete() = sessionName.isNotBlank() && exerciseName.isNotBlank() && numExercise.isNotBlank() && numFile.isNotBlank()
        fun toJson(sOpt: List<String>, eOpt: List<String>) = JSONObject().apply {
            put("s_idx", sOpt.indexOf(sessionName)); put("e_idx", eOpt.indexOf(exerciseName))
            put("n_e", numExercise); put("n_f", numFile); put("f_n", fileName); put("f_sz", fileSizeRaw); put("note", note)
        }
        companion object {
            fun fromJson(j: JSONObject, sOpt: List<String>, eOpt: List<String>): VideoItem {
                val sIdx = j.optInt("s_idx", -1); val eIdx = j.optInt("e_idx", -1)
                return VideoItem(
                    if (sIdx in sOpt.indices) sOpt[sIdx] else "", 
                    j.optString("n_e"), 
                    if (eIdx in eOpt.indices) eOpt[eIdx] else "", 
                    j.optString("n_f"), 
                    j.optString("f_n"), 
                    j.optLong("f_sz"), 
                    j.optString("note")
                )
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
                sN.text = item.sessionName; nE.text = item.numExercise; eN.text = item.exerciseName
                nF.text = item.numFile; fN.text = item.fileName; note.text = item.note
                fS.text = formatFileSize(item.fileSizeRaw)

                sN.setOnClickListener { showOptionsDialog("Сеанс", sessionOptions, pos) { showAddSessionDialog(pos) } }
                nE.setOnClickListener { 
                    if (getItem(pos).sessionName.isEmpty()) Toast.makeText(this@SettingsActivity, getString(R.string.select_session_first), Toast.LENGTH_SHORT).show() 
                    else showExerciseNumPopup(pos) 
                }
                eN.setOnClickListener { showOptionsDialog("Упражнение", exerciseOptions, pos) { showAddExerciseDialog(pos) } }
                nF.setOnClickListener { 
                    if (getItem(pos).exerciseName.isEmpty()) Toast.makeText(this@SettingsActivity, getString(R.string.select_exercise_first), Toast.LENGTH_SHORT).show() 
                    else showFileNumPopup(pos) 
                }
                note.setOnClickListener { showNoteEditDialog(pos) }
            }

            private fun showOptionsDialog(title: String, options: MutableList<String>, pos: Int, onAdd: () -> Unit) {
                val dialogView = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                val listView = ListView(this@SettingsActivity)
                val displayOptions = mutableListOf(getString(R.string.empty_option)); displayOptions.addAll(options)
                val listAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, displayOptions)
                listView.adapter = listAdapter
                listView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                listView.setOnItemClickListener { _, _, i, _ ->
                    val value = if (i == 0) "" else displayOptions[i]
                    val item = getItem(pos)
                    if (title == "Сеанс") updateItem(pos, item.copy(sessionName = value, numExercise = ""))
                    else { 
                        updateItem(pos, item.copy(exerciseName = value, numFile = ""))
                        if (value.isNotEmpty() && !categoryState.containsKey(value)) { categoryState[value] = "000" } 
                    }
                    updateTopInputUI(); alertDialog?.dismiss()
                }
                listView.setOnItemLongClickListener { _, _, i, _ ->
                    if (i == 0) return@setOnItemLongClickListener true
                    val value = displayOptions[i]
                    val builder = AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(if (title == "Упражнение") getString(R.string.exercise_title_format, value) else getString(R.string.delete_option_title))
                    if (title == "Упражнение") {
                        val layout = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
                        val input = EditText(this@SettingsActivity).apply { hint = getString(R.string.counter_hint); setText(categoryState[value] ?: "000"); inputType = InputType.TYPE_CLASS_NUMBER }
                        layout.addView(input); builder.setView(layout)
                        builder.setNeutralButton("Удалить") { _, _ ->
                            options.remove(value); categoryState.remove(value)
                            val newList = currentList.map { 
                                if (it.exerciseName == value) it.copy(exerciseName = "", numFile = "") else it 
                            }
                            saveAndRefresh(newList)
                        }
                        builder.setPositiveButton("Сохранить") { _, _ ->
                            categoryState[value] = input.text.toString().padStart(3, '0')
                            saveAndRefresh(currentList)
                        }
                    } else {
                        builder.setMessage(value).setPositiveButton("Удалить") { _, _ ->
                            options.remove(value)
                            val newList = currentList.map { 
                                if (it.sessionName == value) it.copy(sessionName = "", numExercise = "") else it 
                            }
                            saveAndRefresh(newList)
                        }
                    }
                    builder.setNegativeButton("Отмена", null)
                    val dlg = builder.create()
                    dlg.setOnShowListener { tintDialogButtons(dlg, neutralIsDestructive = title == "Упражнение") }
                    dlg.show(); true
                }
                dialogView.addView(listView)
                val btnAdd = ImageButton(this@SettingsActivity).apply { 
                    setImageResource(android.R.drawable.ic_input_add)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.btn_round_bg)
                    setPadding(8, 8, 8, 8)
                    layoutParams = LinearLayout.LayoutParams(50, 50).apply { gravity = Gravity.CENTER; topMargin = 16 }
                    setOnClickListener { onAdd(); alertDialog?.dismiss() } 
                }
                dialogView.addView(btnAdd)
                alertDialog = AlertDialog.Builder(this@SettingsActivity).setTitle(title).setView(dialogView).create()
                alertDialog?.show()
                val lp = WindowManager.LayoutParams(); lp.copyFrom(alertDialog?.window?.attributes)
                val charWidth = 30
                val widthPx = (charWidth * resources.displayMetrics.scaledDensity * 10).toInt()
                lp.width = Math.min(widthPx, (resources.displayMetrics.widthPixels * 0.9).toInt())
                alertDialog?.window?.attributes = lp
            }
            private var alertDialog: AlertDialog? = null

            private fun saveAndRefresh(newList: List<VideoItem>) {
                val folder = getFolderDocumentFile() ?: return
                saveToConfig(folder, newList); loadUIFromConfig()
            }

            private fun showExerciseNumPopup(pos: Int) {
                val popup = PopupMenu(this@SettingsActivity, nE)
                popup.menu.add(getString(R.string.empty_option))
                val currentSess = getItem(pos).sessionName
                val usedNums = currentList.filter { it.sessionName == currentSess && it.sessionName.isNotEmpty() }.map { it.numExercise }.toSet()
                val freeNums = generateFreeNumbers(usedNums, 1, 99, "%02d")
                freeNums.forEach { popup.menu.add(it) }
                popup.setOnMenuItemClickListener {
                    updateItem(pos, getItem(pos).copy(numExercise = if (it.title == getString(R.string.empty_option)) "" else it.title.toString()))
                    updateTopInputUI()
                    true 
                }
                popup.applyPopupMinWidth(80)
                popup.show()
            }

            private fun showFileNumPopup(pos: Int) {
                val popup = PopupMenu(this@SettingsActivity, nF)
                popup.menu.add(getString(R.string.empty_option))
                val usedNums = currentList.filter { it.exerciseName == getItem(pos).exerciseName && it.exerciseName.isNotEmpty() }.map { it.numFile }.toSet()
                val freeNums = generateFreeNumbers(usedNums, 1, 999, "%03d")
                freeNums.forEach { popup.menu.add(it) }
                popup.setOnMenuItemClickListener {
                    updateItem(pos, getItem(pos).copy(numFile = if (it.title == getString(R.string.empty_option)) "" else it.title.toString()))
                    updateTopInputUI()
                    true 
                }
                popup.applyPopupMinWidth(120)
                popup.show()
            }

            private fun showNoteEditDialog(pos: Int) {
                val input = EditText(this@SettingsActivity).apply { setText(getItem(pos).note) }
                val dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.note_title))
                    .setView(input)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                        updateItem(pos, getItem(pos).copy(note = input.text.toString()))
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
                    setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    setColorFilter(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_green_dark), PorterDuff.Mode.SRC_IN)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.btn_round_bg)
                    layoutParams = LinearLayout.LayoutParams(50, 50).apply { gravity = Gravity.CENTER } 
                }
                val numDisplay = TextView(this@SettingsActivity).apply { text = "№ не выбран"; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
                numBtn.setOnClickListener { btn ->
                    val pop = PopupMenu(this@SettingsActivity, btn)
                    val usedNums = sessionOptions.map { it.split(" ")[0] }.toSet()
                    for (i in 1..9) { val n = i.toString(); if (!usedNums.contains(n)) pop.menu.add(n) }
                    pop.setOnMenuItemClickListener { selectedNum = it.title.toString(); numDisplay.text = "Выбран № $selectedNum"; true }; pop.show()
                }
                val nameInput = EditText(this@SettingsActivity).apply { hint = "Название" }
                val nameHint = getString(R.string.name_hint)
                nameInput.hint = nameHint
                layout.addView(numBtn); layout.addView(numDisplay); layout.addView(nameInput)
                val dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                        val name = nameInput.text.toString().trim()
                        if (selectedNum.isEmpty()) {
                            Toast.makeText(this@SettingsActivity, getString(R.string.select_number), Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val usedNames = sessionOptions.map { it.substringAfter(" ") }.toSet()
                        if (usedNames.contains(name)) {
                            Toast.makeText(this@SettingsActivity, getString(R.string.name_must_be_unique), Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val combined = "$selectedNum $name"
                        if (!sessionOptions.contains(combined)) {
                            sessionOptions.add(combined)
                            sessionOptions.sort()
                        }
                        updateItem(pos, getItem(pos).copy(sessionName = combined))
                    }
                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                    .setView(layout)
                    .setTitle(getString(R.string.new_session))
                    .create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }
                dialog.show()
            }

            private fun showAddExerciseDialog(pos: Int) {
                val input = EditText(this@SettingsActivity).apply { hint = getString(R.string.exercise_name_hint) }
                val dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            if (!exerciseOptions.contains(name)) {
                                exerciseOptions.add(name)
                                exerciseOptions.sort()
                                categoryState[name] = "000" // Всегда 000 при добавлении
                            }
                            updateItem(pos, getItem(pos).copy(exerciseName = name))
                        }
                    }
                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                    .setView(input)
                    .setTitle(getString(R.string.new_exercise))
                    .create()
                dialog.setOnShowListener { tintDialogButtons(dialog) }
                dialog.show()
            }

            private fun updateItem(pos: Int, newItem: VideoItem) {
                val newList = currentList.toMutableList()
                newList[pos] = newItem
                val folder = getFolderDocumentFile() ?: return
                saveToConfig(folder, newList); loadUIFromConfig()
            }
        }
    }
    
    private fun getFolderDocumentFile(): DocumentFile? {
        val folderUri = getFolderUri() ?: return null
        return DocumentFile.fromTreeUri(this, folderUri)
    }

    private fun readConfigJson(configFile: DocumentFile): JSONObject? {
        return try {
            contentResolver.openInputStream(configFile.uri)?.use { inputStream ->
                JSONObject(inputStream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения конфигурации", e)
            null
        }
    }

    private fun tintDialogButtons(dialog: AlertDialog, neutralIsDestructive: Boolean = false) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        if (neutralIsDestructive) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.RED)
        }
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
        // 1. Точное совпадение имени
        folder.findFile(CONFIG_FILE_NAME)?.let { return it }

        // 2. Файлы, которые создал SAF: texfit.cfg.json, texfit.cfg (1).json и т.п.
        return folder.listFiles().firstOrNull { file ->
            val name = file.name ?: return@firstOrNull false
            name == CONFIG_FILE_NAME || name.startsWith("$CONFIG_FILE_NAME.")
        }
    }

    private fun findOrCreateConfigFile(folder: DocumentFile): DocumentFile? {
        // Пытаемся переиспользовать уже существующий файл (включая варианты с .json)
        findConfigFileForRead(folder)?.let { return it }
        // Иначе создаём новый
        return folder.createFile("application/json", CONFIG_FILE_NAME)
    }
}

private fun PopupMenu.applyPopupMinWidth(widthPx: Int) {
    try {
        val popupClazz = this.javaClass
        val mPopupField = popupClazz.getDeclaredField("mPopup")
        mPopupField.isAccessible = true
        val menuPopupHelper = mPopupField.get(this) ?: return
        val helperClazz = menuPopupHelper.javaClass
        val setMinWidthMethod = helperClazz.getDeclaredMethod("setMinWidth", Int::class.java)
        setMinWidthMethod.invoke(menuPopupHelper, widthPx)
    } catch (e: Exception) { Log.e("SettingsActivity", "Popup width error", e) }
}

private fun PopupMenu.applyPopupForceShowIcon() {
    try {
        val popupClazz = this.javaClass
        val mPopupField = popupClazz.getDeclaredField("mPopup")
        mPopupField.isAccessible = true
        val menuPopupHelper = mPopupField.get(this) ?: return
        val helperClazz = menuPopupHelper.javaClass
        val setForceShowIconMethod = helperClazz.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
        setForceShowIconMethod.invoke(menuPopupHelper, true)
    } catch (e: Exception) { Log.e("SettingsActivity", "Popup icon error", e) }
}

class VideoItemDiffCallback : DiffUtil.ItemCallback<SettingsActivity.VideoItem>() {
    override fun areItemsTheSame(oldItem: SettingsActivity.VideoItem, newItem: SettingsActivity.VideoItem) = oldItem.fileName == newItem.fileName
    override fun areContentsTheSame(oldItem: SettingsActivity.VideoItem, newItem: SettingsActivity.VideoItem) = oldItem == newItem
}

package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * File browser rooted at the app's home folder.
 * Tap a folder to enter, tap a file to open in Editor,
 * long-press a file to run it in the Console.
 * Import brings a file in from shared storage (Downloads etc.) via the
 * system document picker (SAF) — no broad storage permission needed.
 */
class ProgramsActivity : Activity() {

    companion object {
        private const val IMPORT_REQUEST = 1001
        private const val QPYTHON_TREE_REQUEST = 1002
        private const val QPYTHON_PERM_REQUEST = 1003
        private const val PREFS = "dz_terminal_prefs"
        private const val KEY_QPYTHON_TREE = "qpython_tree_uri"
        private const val KEY_QPYTHON_DIR = "qpython_dir"
        /** Default QPython scripts folder (v1.0.11, user-confirmed). */
        private const val DEFAULT_QPYTHON_DIR = "/sdcard/qpython/scripts3"
    }

    private lateinit var listView: ListView
    private lateinit var titleText: TextView
    private lateinit var currentDir: File
    private val shown = ArrayList<File>()

    /** QPython mode (v1.0.10): lists .py files from the user's
     *  QPython scripts folder instead of the app home folder. */
    private var qpythonMode = false
    /** True when QPython mode uses the direct /sdcard folder
     *  (v1.0.11 primary path, storage permission granted). False
     *  means the SAF tree fallback. */
    private var qpythonDirect = false
    private data class QPythonDoc(val docId: String, val name: String)
    private val qpythonDocs = ArrayList<QPythonDoc>()
    private val qpythonFiles = ArrayList<File>()
    private var qpythonEmptyMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_programs)
        listView = findViewById(R.id.programs_list)
        titleText = findViewById(R.id.programs_title)

        findViewById<Button>(R.id.programs_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.programs_new).setOnClickListener { newFileDialog() }
        findViewById<Button>(R.id.programs_import).setOnClickListener { importFile() }
        findViewById<Button>(R.id.programs_choose_qpython).setOnClickListener {
            // Direct mode: change the folder path. SAF fallback:
            // pick a folder with the system picker.
            if (qpythonDirect) changeQpythonFolderDialog() else chooseQpythonFolder()
        }
        findViewById<Button>(R.id.programs_qpython).setOnClickListener {
            qpythonMode = !qpythonMode
            if (qpythonMode) {
                // Home-only actions make no sense for the SAF folder:
                // hide them, show the folder chooser instead.
                findViewById<Button>(R.id.programs_up).visibility = View.GONE
                findViewById<Button>(R.id.programs_import).visibility = View.GONE
                findViewById<Button>(R.id.programs_new).visibility = View.GONE
                findViewById<Button>(R.id.programs_choose_qpython).visibility = View.VISIBLE
                findViewById<Button>(R.id.programs_qpython).text = getString(R.string.home_files)
                ensureQpythonPermission()
            } else {
                findViewById<Button>(R.id.programs_up).visibility = View.VISIBLE
                findViewById<Button>(R.id.programs_import).visibility = View.VISIBLE
                findViewById<Button>(R.id.programs_new).visibility = View.VISIBLE
                findViewById<Button>(R.id.programs_choose_qpython).visibility = View.GONE
                findViewById<Button>(R.id.programs_qpython).text = getString(R.string.qpython_scripts)
                showDir(currentDir)
            }
        }
        findViewById<Button>(R.id.programs_up).setOnClickListener {
            val app = application as DzTerminalApp
            val home = File(DzTerminalApp.hostHome(app))
            // Stay rooted at home: never navigate above it.
            val parent = currentDir.parentFile
            if (currentDir != home && parent != null &&
                parent.absolutePath.startsWith(home.absolutePath)
            ) {
                showDir(parent)
            }
        }

        val app = application as DzTerminalApp
        val home = File(DzTerminalApp.hostHome(app))
        home.mkdirs()
        showDir(home)

        listView.setOnItemClickListener { _, _, position, _ ->
            if (qpythonMode) {
                // The placeholder row opens the folder chooser.
                if (qpythonEmptyMessage != null) {
                    if (qpythonDirect) changeQpythonFolderDialog() else chooseQpythonFolder()
                    return@setOnItemClickListener
                }
                if (qpythonDirect) {
                    val f = qpythonFiles.getOrNull(position) ?: return@setOnItemClickListener
                    runQpythonFile(f)
                    return@setOnItemClickListener
                }
                val doc = qpythonDocs.getOrNull(position) ?: return@setOnItemClickListener
                runQpythonDoc(doc)
                return@setOnItemClickListener
            }
            val f = shown[position]
            if (f.isDirectory) {
                showDir(f)
            } else {
                val intent = Intent(this, EditorActivity::class.java)
                intent.putExtra(EditorActivity.EXTRA_PATH, f.absolutePath)
                startActivity(intent)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            // No long-press action in QPython mode (tap runs the file).
            if (qpythonMode) return@setOnItemLongClickListener true
            val f = shown[position]
            if (!f.isDirectory) {
                // v1.0.21: Programs script-run opens a FRESH
                // Console tab (QPython behavior), cds to the
                // script's dir, runs `python <file>` there.
                val runner = if (f.name.endsWith(".py")) "python" else "bash"
                runScriptInNewTab(runner, f.absolutePath)
            }
            true
        }
    }

    private fun showDir(dir: File) {
        currentDir = dir
        titleText.text = dir.name.ifEmpty { "Home" }
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        shown.clear()
        shown.addAll(children)
        val labels = children.map { if (it.isDirectory) it.name + "/" else it.name }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    // ---- QPython scripts ----
    //
    // v1.0.11 PRIMARY: direct read of the QPython scripts folder
    // (default /sdcard/qpython/scripts3, user-changeable) with the
    // storage permission — the user confirmed it is already granted.
    // At targetSdk 28 READ_EXTERNAL_STORAGE grants broad read, and
    // the direct path is exactly what the shell itself sees via
    // storage/shared/, so tap-to-run needs no temp copy.
    // FALLBACK: the v1.0.10 SAF tree picker, used only when the
    // storage permission is denied.
    //
    // Listing is flat (the folder only, not recursive), .py files
    // sorted by name. Tap runs the file in the Console.

    /** Dispatch: direct folder when permitted, SAF tree otherwise. */
    private fun showQpython() {
        titleText.text = getString(R.string.qpython_scripts)
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showQpythonDirect()
        } else {
            showQpythonSaf()
        }
    }

    /** Asks for the storage permission when entering QPython mode
     *  (only if not already granted). Denial falls back to SAF. */
    private fun ensureQpythonPermission() {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showQpython()
            return
        }
        Toast.makeText(this, R.string.qpython_storage_need, Toast.LENGTH_LONG).show()
        requestPermissions(
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            QPYTHON_PERM_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == QPYTHON_PERM_REQUEST && qpythonMode) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                showQpython()
            } else {
                Toast.makeText(this, R.string.qpython_using_saf, Toast.LENGTH_LONG).show()
                showQpython()
            }
        }
    }

    /** Current QPython folder (direct mode), persisted in prefs. */
    private fun qpythonDir(): File {
        val path = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_QPYTHON_DIR, DEFAULT_QPYTHON_DIR) ?: DEFAULT_QPYTHON_DIR
        return File(path)
    }

    /** Direct listing of the QPython folder (permission granted). */
    private fun showQpythonDirect() {
        qpythonDirect = true
        qpythonFiles.clear()
        qpythonEmptyMessage = null
        titleText.text = getString(R.string.qpython_scripts)
        val dir = qpythonDir()
        if (!dir.isDirectory || !dir.canRead()) {
            showQpythonEmpty(getString(R.string.qpython_folder_not_found) + ": " + dir.absolutePath)
            return
        }
        val py = dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".py", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        qpythonFiles.addAll(py)
        if (py.isEmpty()) {
            showQpythonEmpty(getString(R.string.qpython_no_py))
        } else {
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1, py.map { it.name }
            )
        }
    }

    /** Runs a QPython .py file in the Console (direct mode). The
     *  direct path is same-UID-readable, exactly what the shell
     *  itself sees — no temp copy needed. */
    private fun runQpythonFile(f: File) {
        runScriptInNewTab("python", f.absolutePath)
    }

    /**
     * v1.0.21: Programs script-run — QPython behavior. EVERY run
     * opens a FRESH Console tab (never reuses an existing tab),
     * cds to the script's directory, and runs
     * `python <filename>` (or `bash <filename>` for shell
     * scripts) there, so relative paths / imports resolve
     * against the script's own folder.
     *
     * Path form: the Console shell runs UNDER PROOT with the
     * guest view, so a file under our prefix must be addressed
     * by its GUEST path (fake prefix), not the host path — a
     * host path would be rootfs-prepended by proot and not
     * found. /sdcard and /storage paths pass through unchanged
     * (bound). Anything else is passed as-is (best effort).
     */
    private fun runScriptInNewTab(runner: String, absPath: String) {
        val guest = guestPathFor(absPath)
        val dir = guest.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = guest.substringAfterLast('/')
        val cmd = "cd \"${shellQuote(dir)}\" && $runner \"${shellQuote(name)}\""
        ConsoleActivity.openWithCommandInNewTab(this, cmd)
    }

    /** Maps a host absolute path onto the path the proot guest sees. */
    private fun guestPathFor(absPath: String): String {
        val hostUsr = DzTerminalApp.hostUsr(application as DzTerminalApp)
        return when {
            absPath == hostUsr -> DzTerminalApp.GUEST_PREFIX
            absPath.startsWith("$hostUsr/") ->
                DzTerminalApp.GUEST_PREFIX + absPath.removePrefix(hostUsr)
            else -> absPath
        }
    }

    /** Escapes backslash + double-quote for a "..."-quoted shell word. */
    private fun shellQuote(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Change-folder dialog (direct mode): type the folder path. */
    private fun changeQpythonFolderDialog() {
        val input = EditText(this)
        input.setText(qpythonDir().absolutePath)
        AlertDialog.Builder(this)
            .setTitle(R.string.qpython_change_folder)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isEmpty() || !path.startsWith("/") || path.contains("..")) {
                    Toast.makeText(this, R.string.qpython_folder_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_QPYTHON_DIR, path).apply()
                showQpythonDirect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** SAF tree fallback (v1.0.10): used only when the storage
     *  permission is denied. Folder picked with the SAF tree picker;
     *  the tree URI + persistable permission survive restarts. */
    private fun showQpythonSaf() {
        qpythonDirect = false
        titleText.text = getString(R.string.qpython_scripts)
        qpythonDocs.clear()
        qpythonEmptyMessage = null
        val uriString = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_QPYTHON_TREE, null)
        if (uriString.isNullOrEmpty()) {
            showQpythonEmpty(getString(R.string.qpython_not_set))
            return
        }
        try {
            val treeUri = Uri.parse(uriString)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idIdx < 0 || nameIdx < 0) break
                    val name = cursor.getString(nameIdx) ?: continue
                    if (!name.endsWith(".py", ignoreCase = true)) continue
                    qpythonDocs.add(QPythonDoc(cursor.getString(idIdx), name))
                }
            }
            qpythonDocs.sortBy { it.name.lowercase() }
            if (qpythonDocs.isEmpty()) {
                showQpythonEmpty(getString(R.string.qpython_no_py))
            } else {
                listView.adapter = ArrayAdapter(
                    this, android.R.layout.simple_list_item_1, qpythonDocs.map { it.name }
                )
            }
        } catch (_: Exception) {
            // Revoked permission / missing folder / provider error:
            // prompt to re-pick instead of crashing or showing a
            // silent empty list.
            showQpythonEmpty(getString(R.string.qpython_not_set))
            Toast.makeText(this, R.string.qpython_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showQpythonEmpty(message: String) {
        qpythonEmptyMessage = message
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, listOf(message)
        )
    }

    /** Opens the SAF tree picker for the QPython scripts folder. */
    private fun chooseQpythonFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, QPYTHON_TREE_REQUEST)
    }

    /** Runs a QPython .py file in the Console. */
    private fun runQpythonDoc(doc: QPythonDoc) {
        val treeUriString = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_QPYTHON_TREE, null) ?: return
        val treeUri = Uri.parse(treeUriString)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.docId)
        // Prefer a direct path when the tree is under /sdcard — but
        // only when the shell can actually read it (same UID as this
        // process, so canRead() predicts the shell's read exactly).
        // The app holds no storage permission, so a /sdcard path is
        // usually NOT readable and the copy fallback below applies.
        val directPath = documentIdToSdcardPath(doc.docId)
        if (directPath != null && File(directPath).canRead()) {
            runScriptInNewTab("python", directPath)
            return
        }
        // Otherwise copy the file's bytes to a temp file INSIDE
        // the prefix (v1.0.21: the old filesDir/qpython_cache
        // copy sat outside the proot bind, so the guest could
        // not see it — prefix tmp/ is visible via the bind).
        // Fresh copy on every run, so QPython edits apply.
        try {
            val cacheDir = File(
                DzTerminalApp.hostUsr(application as DzTerminalApp),
                "tmp/qpython_cache"
            ).apply { mkdirs() }
            val dest = File(cacheDir, doc.name.replace(Regex("[/\\\\\u0000]"), "_"))
            contentResolver.openInputStream(docUri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: throw RuntimeException("Could not open picked file")
            runScriptInNewTab("python", dest.absolutePath)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not run: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Maps a SAF document ID to a shell-visible path. "primary:<rel>"
     * -> /sdcard/<rel>. Other volumes have no stable shell-visible
     * path -> null (caller falls back to a temp copy).
     */
    private fun documentIdToSdcardPath(docId: String): String? {
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:").trimStart('/')
        if (rel.isEmpty() || rel.contains("..")) return null
        return "/sdcard/$rel"
    }

    /** Opens the system document picker (SAF) to import one file. */
    private fun importFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, IMPORT_REQUEST)
    }

    @Deprecated("Use onActivityResult for the SAF pickers (no androidx in this app)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == QPYTHON_TREE_REQUEST) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Persistable permission is best-effort; the listing
                // below still works for this session.
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_QPYTHON_TREE, uri.toString()).apply()
            if (qpythonMode) showQpython()
            Toast.makeText(this, R.string.qpython_folder_set, Toast.LENGTH_SHORT).show()
            return
        }
        if (requestCode != IMPORT_REQUEST) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Persistable permission is best-effort; the one-time read below still works.
        }
        try {
            val rawName = queryDisplayName(uri) ?: "imported_file"
            val dest = uniqueFile(currentDir, sanitizeFileName(rawName))
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: throw RuntimeException("Could not open picked file")
            showDir(currentDir)
            Toast.makeText(this, "Imported: " + dest.name, Toast.LENGTH_SHORT).show()
            val intent = Intent(this, EditorActivity::class.java)
            intent.putExtra(EditorActivity.EXTRA_PATH, dest.absolutePath)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(raw: String): String {
        var name = raw.replace(Regex("[/\\\\\u0000]"), "_").trim()
        if (name.isEmpty() || name == "." || name == "..") name = "imported_file"
        return name
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    private fun newFileDialog() {
        val input = EditText(this)
        input.hint = "new_script.py"
        AlertDialog.Builder(this)
            .setTitle("New file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val f = File(currentDir, name)
                try {
                    if (f.exists()) {
                        Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    f.writeText(if (name.endsWith(".py")) "# new script\n" else "")
                    showDir(currentDir)
                    val intent = Intent(this, EditorActivity::class.java)
                    intent.putExtra(EditorActivity.EXTRA_PATH, f.absolutePath)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

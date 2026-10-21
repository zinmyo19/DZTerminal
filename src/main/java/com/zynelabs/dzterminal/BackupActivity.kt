package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v1.0.49: Backup — one-tap zip backups of the DZ Terminal
 * home folder into shared storage
 * (/storage/emulated/0/DZTerminal/backups), with restore.
 * A backup is an ordinary .zip file the user can also open
 * with any file manager.
 */
class BackupActivity : Activity() {

    private lateinit var listView: ListView
    private var backups: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        listView = findViewById(R.id.backup_list)
        findViewById<Button>(R.id.backup_menu_button).setOnClickListener { v -> showMenu(v) }
        findViewById<Button>(R.id.backup_create).setOnClickListener { createBackup() }
        listView.setOnItemClickListener { _, _, pos, _ -> confirmRestore(backups[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(backups[pos])
            true
        }
        if (!hasStoragePermission()) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERM_REQUEST
            )
        }
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERM_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                refresh()
            } else {
                Toast.makeText(
                    this,
                    "grant Files and media in App info to use Backup",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hasStoragePermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Backup").setEnabled(false)
        popup.menu.add("Refresh")
        popup.menu.add("Home")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Refresh" -> { refresh(); true }
                "Home" -> { finish(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun backupDir(): File = File(HomeFileSync.exportDir(), "backups")

    private fun refresh() {
        val dir = backupDir()
        backups = if (dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else {
            emptyList()
        }
        listView.adapter = object : ArrayAdapter<File>(this, 0, backups) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val f = getItem(pos)!!
                val stack = LinearLayout(context)
                stack.orientation = LinearLayout.VERTICAL
                stack.setPadding(dp(14), dp(10), dp(14), dp(10))
                val name = TextView(context)
                name.text = f.name
                name.textSize = 15f
                name.typeface = Typeface.DEFAULT_BOLD
                name.setTextColor(getColor(R.color.dz_white))
                val meta = TextView(context)
                meta.text = formatBytes(f.length()) + "  -  " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
                meta.textSize = 12f
                meta.setTextColor(getColor(R.color.dz_muted))
                stack.addView(name)
                stack.addView(meta)
                return stack
            }
        }
    }

    // ---- create / restore / delete ----

    private fun createBackup() {
        if (!hasStoragePermission()) {
            Toast.makeText(
                this,
                "grant Files and media in App info to use Backup",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val home = File(DzTerminalApp.hostHome(application as DzTerminalApp))
        val dir = backupDir()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val outFile = File(dir, "dz-home-$stamp.zip")
        val progress = progressDialog("Creating backup...")
        Thread {
            try {
                dir.mkdirs()
                val result = zipHome(home, outFile)
                runOnUiThread {
                    progress.dismiss()
                    refresh()
                    infoDialog(
                        "Backup done",
                        result.first.toString() + " file(s), " +
                            formatBytes(result.second) + "\n\n" + outFile.absolutePath
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    infoDialog("Backup failed", e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun confirmRestore(zip: File) {
        AlertDialog.Builder(this)
            .setTitle("Restore backup?")
            .setMessage(
                zip.name + "\n\nFiles in your DZ Terminal home will be " +
                    "overwritten with the versions from this backup."
            )
            .setPositiveButton("Restore") { _, _ -> doRestore(zip) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doRestore(zip: File) {
        val home = File(DzTerminalApp.hostHome(application as DzTerminalApp))
        val progress = progressDialog("Restoring...")
        Thread {
            try {
                val count = unzipInto(zip, home)
                runOnUiThread {
                    progress.dismiss()
                    infoDialog("Restore done", "$count file(s) restored.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    infoDialog("Restore failed", e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun confirmDelete(zip: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete backup?")
            .setMessage(zip.name)
            .setPositiveButton("Delete") { _, _ ->
                zip.delete()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- zip helpers ----

    private fun zipHome(src: File, outFile: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(src).path))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
                bytes += f.length()
            }
        }
        return Pair(count, bytes)
    }

    private fun unzipInto(zip: File, dest: File): Int {
        var count = 0
        val destCanon = dest.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                val canon = out.canonicalPath
                if (canon != destCanon && !canon.startsWith(destCanon + File.separator)) {
                    throw IllegalStateException("Unsafe entry: " + entry.name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    // ---- small UI helpers ----

    private fun progressDialog(message: String): AlertDialog {
        val d = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        d.show()
        return d
    }

    private fun infoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB")
        var v = b.toDouble()
        var u = -1
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024
            u++
        }
        return String.format(Locale.US, "%.1f %s", v, units[u])
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val STORAGE_PERM_REQUEST = 1007
    }
}

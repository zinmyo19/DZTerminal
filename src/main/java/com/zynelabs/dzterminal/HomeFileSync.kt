package com.zynelabs.dzterminal

import android.app.Application
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * v1.0.28: Export / Import the app's HOME files to shared storage
 * so they show up in the file manager.
 *
 * Why this exists: home lives in the app's PRIVATE dir
 * (/data/data/com.zynelabs.dzterminal/files/usr/home), which no
 * file manager can see without root. Shared storage
 * (/storage/emulated/0/...) IS visible. Export copies home ->
 * /storage/emulated/0/DZTerminal/; Import copies it back, so
 * edits made in the file manager round-trip into the shell.
 *
 * Destination is the TOP LEVEL of shared storage —
 * /storage/emulated/0/DZTerminal/ (the level the file manager
 * shows) — never inside any user/Android/data folder, per the
 * user's explicit instruction.
 *
 * Safety rules (hard):
 * - COPY, never move: the source is never deleted.
 * - Only HOME is ever touched (user files: PYTHON/, scripts,
 *   documents he created). The prefix (bin/, lib/, etc.) is
 *   NEVER exported/imported — binaries cannot live on shared
 *   storage and must stay private.
 * - The real Termux app's data is NEVER read or written.
 * - Symlinks are NEVER followed. Home contains a `storage`
 *   symlink -> /storage/emulated/0 (v1.0.27 guest shortcut):
 *   following it would recursively copy ALL of shared storage
 *   into /sdcard/DZTerminal/ (including DZTerminal itself).
 *   Symlinks are skipped and counted.
 * - __pycache__ dirs and *.tmp files are skipped (regenerable
 *   / scratch), counted as skipped.
 * - Import never overwrites blindly: a file that exists in
 *   home with different size/mtime is kept, and the shared
 *   copy lands as <name>.imported (with -2, -3 suffixes if
 *   needed). Identical files (same size + mtime) are skipped.
 * - mtimes are preserved on every copy, so a clean round-trip
 *   (export -> import with no edits) is a no-op on import.
 */
object HomeFileSync {

    const val EXPORT_DIR_NAME = "DZTerminal"

    /**
     * Shared-storage export dir: /storage/emulated/0/DZTerminal/
     * (Environment.getExternalStorageDirectory() resolves to
     * /storage/emulated/0 on this device). Top level of shared
     * storage — the level the file manager shows.
     */
    fun exportDir(): File = File(Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)

    /** Display path for dialogs/Debug (the literal shared-storage path). */
    const val EXPORT_DIR_DISPLAY = "/storage/emulated/0/DZTerminal"

    data class ExportResult(
        val filesCopied: Int,
        val bytesCopied: Long,
        val filesSkipped: Int,
        val errors: List<String>
    ) {
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("Exported $filesCopied file(s) (${formatBytes(bytesCopied)}) to $EXPORT_DIR_DISPLAY")
            if (filesSkipped > 0) sb.append("\nSkipped $filesSkipped (symlinks, __pycache__, *.tmp)")
            if (errors.isNotEmpty()) {
                sb.append("\n${errors.size} error(s):\n")
                sb.append(errors.take(5).joinToString("\n"))
                if (errors.size > 5) sb.append("\n...and ${errors.size - 5} more")
            }
            return sb.toString()
        }
    }

    data class ImportResult(
        val filesImported: Int,
        val bytesImported: Long,
        val filesSkippedIdentical: Int,
        val conflictsRenamed: List<String>,
        val filesSkipped: Int,
        val errors: List<String>
    ) {
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("Imported $filesImported file(s) (${formatBytes(bytesImported)}) into home")
            if (filesSkippedIdentical > 0) sb.append("\nSkipped $filesSkippedIdentical identical file(s)")
            if (conflictsRenamed.isNotEmpty()) {
                sb.append("\n${conflictsRenamed.size} conflict(s) kept as .imported:\n")
                sb.append(conflictsRenamed.take(5).joinToString("\n"))
                if (conflictsRenamed.size > 5) sb.append("\n...and ${conflictsRenamed.size - 5} more")
            }
            if (filesSkipped > 0) sb.append("\nSkipped $filesSkipped (symlinks, __pycache__, *.tmp)")
            if (errors.isNotEmpty()) {
                sb.append("\n${errors.size} error(s):\n")
                sb.append(errors.take(5).joinToString("\n"))
                if (errors.size > 5) sb.append("\n...and ${errors.size - 5} more")
            }
            return sb.toString()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun isSkippable(file: File): Boolean {
        if (file.name == "__pycache__") return true
        if (file.isFile && file.name.endsWith(".tmp")) return true
        return false
    }

    /** Copy one file, preserving mtime. Returns bytes copied, or -1 on failure. */
    private fun copyFile(src: File, dest: File): Long {
        dest.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                var n: Int
                while (input.read(buffer).also { n = it } != -1) {
                    output.write(buffer, 0, n)
                    total += n
                }
                output.flush()
                dest.setLastModified(src.lastModified())
                return total
            }
        }
    }

    /**
     * Export: HOME -> /storage/emulated/0/DZTerminal/.
     * Overwrites existing files in the export dir (it is a
     * mirror of home). Never deletes the source.
     */
    fun exportHome(app: Application): ExportResult {
        val home = File(DzTerminalApp.hostHome(app))
        val dest = exportDir()
        var filesCopied = 0
        var bytesCopied = 0L
        var filesSkipped = 0
        val errors = ArrayList<String>()
        try {
            val homeCanon = home.canonicalFile
            val filesCanon = app.filesDir.canonicalFile
            if (!homeCanon.path.startsWith(filesCanon.path)) {
                return ExportResult(0, 0, 0, listOf("Home dir is not inside app files — refusing (safety)"))
            }
            if (!home.isDirectory) {
                return ExportResult(0, 0, 0, listOf("Home folder not set up yet — open Console first"))
            }
            if (!dest.mkdirs() && !dest.isDirectory) {
                return ExportResult(0, 0, 0, listOf("Could not create $EXPORT_DIR_DISPLAY — grant Files and media permission"))
            }
        } catch (e: Exception) {
            return ExportResult(0, 0, 0, listOf("Export failed to start: ${e.message}"))
        }

        fun visit(src: File, destDir: File) {
            if (Files.isSymbolicLink(src.toPath())) {
                filesSkipped++
                return
            }
            if (isSkippable(src)) {
                filesSkipped++
                return
            }
            if (src.isDirectory) {
                val target = File(destDir, src.name)
                // Never recurse into the export dir itself (paranoia:
                // a bind/symlink could make home contain it).
                try {
                    if (target.canonicalFile == dest.canonicalFile) {
                        filesSkipped++
                        return
                    }
                } catch (_: Exception) {
                }
                target.mkdirs()
                src.listFiles()?.forEach { visit(it, target) }
                return
            }
            if (src.isFile) {
                val target = File(destDir, src.name)
                try {
                    val bytes = copyFile(src, target)
                    filesCopied++
                    bytesCopied += bytes
                } catch (e: Exception) {
                    errors.add("${src.name}: ${e.message}")
                }
            }
        }

        // visit() names children after their parent; the top-level
        // call maps home's children into dest (not home itself).
        home.listFiles()?.forEach { visit(it, dest) }
        val result = ExportResult(filesCopied, bytesCopied, filesSkipped, errors)
        DebugInfo.lastExportSummary = result.summary()
        return result
    }

    /**
     * Import: /storage/emulated/0/DZTerminal/ -> HOME.
     * Conflict rule: existing home file with different size/mtime
     * is KEPT; the shared copy lands as <name>.imported
     * (or <name>.imported-2, ... if taken). Identical files are
     * skipped. Never deletes the source.
     */
    fun importFromShared(app: Application): ImportResult {
        val home = File(DzTerminalApp.hostHome(app))
        val src = exportDir()
        var filesImported = 0
        var bytesImported = 0L
        var filesSkippedIdentical = 0
        var filesSkipped = 0
        val conflictsRenamed = ArrayList<String>()
        val errors = ArrayList<String>()
        try {
            val homeCanon = home.canonicalFile
            val filesCanon = app.filesDir.canonicalFile
            if (!homeCanon.path.startsWith(filesCanon.path)) {
                return ImportResult(0, 0, 0, emptyList(), 0, listOf("Home dir is not inside app files — refusing (safety)"))
            }
            if (!home.isDirectory) {
                return ImportResult(0, 0, 0, emptyList(), 0, listOf("Home folder not set up yet — open Console first"))
            }
            if (!src.isDirectory) {
                return ImportResult(0, 0, 0, emptyList(), 0, listOf("Nothing exported yet — run Export first ($EXPORT_DIR_DISPLAY does not exist)"))
            }
        } catch (e: Exception) {
            return ImportResult(0, 0, 0, emptyList(), 0, listOf("Import failed to start: ${e.message}"))
        }

        fun importFile(srcFile: File, destDir: File) {
            if (Files.isSymbolicLink(srcFile.toPath())) {
                filesSkipped++
                return
            }
            if (isSkippable(srcFile)) {
                filesSkipped++
                return
            }
            if (srcFile.isDirectory) {
                val target = File(destDir, srcFile.name)
                target.mkdirs()
                srcFile.listFiles()?.forEach { importFile(it, target) }
                return
            }
            if (!srcFile.isFile) return
            val target = File(destDir, srcFile.name)
            try {
                if (target.exists()) {
                    if (target.length() == srcFile.length() &&
                        target.lastModified() == srcFile.lastModified()
                    ) {
                        filesSkippedIdentical++
                        return
                    }
                    // Conflict: keep BOTH — shared copy as .imported.
                    var candidate = File(destDir, srcFile.name + ".imported")
                    var n = 2
                    while (candidate.exists()) {
                        candidate = File(destDir, srcFile.name + ".imported-$n")
                        n++
                    }
                    val bytes = copyFile(srcFile, candidate)
                    filesImported++
                    bytesImported += bytes
                    conflictsRenamed.add("${srcFile.name} -> ${candidate.name}")
                } else {
                    val bytes = copyFile(srcFile, target)
                    filesImported++
                    bytesImported += bytes
                }
            } catch (e: Exception) {
                errors.add("${srcFile.name}: ${e.message}")
            }
        }

        src.listFiles()?.forEach { importFile(it, home) }
        val result = ImportResult(
            filesImported, bytesImported, filesSkippedIdentical,
            conflictsRenamed, filesSkipped, errors
        )
        DebugInfo.lastImportSummary = result.summary()
        return result
    }
}

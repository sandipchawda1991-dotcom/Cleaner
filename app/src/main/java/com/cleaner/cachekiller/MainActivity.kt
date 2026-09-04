package com.cleaner.cachekiller

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cleaner.cachekiller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isCleaning = false
    private var totalCacheBefore = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateUI()
        binding.btnClean.setOnClickListener { if (!isCleaning) startCleaning() }
        binding.btnEnableAccessibility.setOnClickListener { showAccessibilityDialog() }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        bytes > 0               -> "$bytes B"
        else                    -> "0 KB"
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        if (isAccessibilityEnabled()) {
            binding.tvStatus.text = "✅ Accessibility ON"
            binding.tvStatus.setTextColor(getColor(R.color.green))
            binding.btnEnableAccessibility.text = "Accessibility: ON ✓"
            binding.btnEnableAccessibility.isEnabled = false
            binding.btnClean.isEnabled = true
        } else {
            binding.tvStatus.text = "⚠️ Enable Accessibility to begin"
            binding.tvStatus.setTextColor(getColor(R.color.orange))
            binding.btnEnableAccessibility.text = "Enable Accessibility"
            binding.btnEnableAccessibility.isEnabled = true
            binding.btnClean.isEnabled = false
        }
    }

    private fun setStage(pct: Int, stage: String, sub: String, color: Int) {
        binding.tvPercentage.text = "$pct%"
        binding.tvPercentage.setTextColor(getColor(color))
        binding.tvStageLabel.text = stage
        binding.tvProgress.text = sub
        binding.progressBar.progress = pct
    }

    // ── Main cleaning flow ────────────────────────────────────────────────────

    private fun startCleaning() {
        isCleaning = true
        binding.btnClean.isEnabled = false
        binding.tvAppsCleared.text = "0"
        binding.tvAppsKilled.text  = "0"
        binding.tvMbCleared.text   = "0 MB"

        lifecycleScope.launch {

            // 1. Scan
            setStage(0, "SCANNING", "Measuring cache & trash...", R.color.orange)
            val apps = getInstalledApps()
            totalCacheBefore = measureTotalCache(apps) + measureTrashSize()
            binding.tvAppCount.text = "${apps.size} apps · ${formatSize(totalCacheBefore)} found"
            delay(300)

            // 2. Kill background apps
            setStage(8, "CLOSING APPS", "Killing background processes...", R.color.orange)
            val killed = killAllBackgroundApps()
            binding.tvAppsKilled.text = "$killed"
            delay(300)

            // 3. Delete recycle bin / trash
            setStage(15, "EMPTYING TRASH", "Deleting recycle bin & temp files...", R.color.orange)
            val trashFreed = deleteTrashAndTemp()
            binding.tvMbCleared.text = formatSize(trashFreed)
            binding.tvAppCount.text = "Trash: ${formatSize(trashFreed)} cleared"
            delay(300)

            // 4. Clear app caches via Accessibility
            setStage(20, "PROCESSING", "Clearing app caches...", R.color.green)

            CacheClearService.appPackages  = apps.toMutableList()
            CacheClearService.totalApps    = apps.size
            CacheClearService.currentIndex = 0

            CacheClearService.progressCallback = { current, total ->
                runOnUiThread {
                    val pct = 20 + ((current.toFloat() / total) * 75).toInt()
                    val estimated = trashFreed + ((totalCacheBefore - trashFreed) * current / total)
                    setStage(pct, "PROCESSING", "App $current of $total...", R.color.green)
                    binding.tvAppsCleared.text = "$current"
                    binding.tvAppCount.text = "$current / $total apps"
                    binding.tvMbCleared.text = formatSize(estimated)
                }
            }

            CacheClearService.doneCallback = {
                runOnUiThread {
                    lifecycleScope.launch { onCleaningDone(apps.size, killed, trashFreed) }
                }
            }

            if (apps.isNotEmpty()) openAppStorageSettings(apps[0])
            else lifecycleScope.launch { onCleaningDone(0, killed, trashFreed) }
        }
    }

    private suspend fun onCleaningDone(appCount: Int, killed: Int, trashFreed: Long) {
        val apps = getInstalledApps()
        val cacheAfter = measureTotalCache(apps)
        val totalFreed = maxOf(trashFreed, totalCacheBefore - cacheAfter + trashFreed)

        isCleaning = false
        setStage(100, "COMPLETED ✓", "${formatSize(totalFreed)} freed", R.color.green)
        binding.tvAppsCleared.text = "$appCount"
        binding.tvAppsKilled.text  = "$killed"
        binding.tvMbCleared.text   = formatSize(totalFreed)
        binding.tvAppCount.text    = "$appCount apps · trash · ${formatSize(totalFreed)} total freed"
        binding.btnClean.isEnabled = true
        binding.tvStatus.text      = "✅ Accessibility ON"
        binding.tvStatus.setTextColor(getColor(R.color.green))
        Toast.makeText(this, "🎉 ${formatSize(totalFreed)} freed!", Toast.LENGTH_LONG).show()
    }

    // ── Trash / recycle bin deletion ──────────────────────────────────────────

    /**
     * Deletes all known trash / recycle bin / temp locations Android exposes
     * to third-party apps (no root needed for these).
     */
    private suspend fun deleteTrashAndTemp(): Long = withContext(Dispatchers.IO) {
        var freed = 0L

        // Folders to wipe
        val trashPaths = mutableListOf<File>()

        // 1. External storage trash folders used by file managers
        val extRoot = Environment.getExternalStorageDirectory()
        listOf(
            ".Trash", ".trash",
            ".Recycle", ".recycle",
            "LOST.DIR",
            ".thumbnails",
            "Android/data/com.miui.gallery/cache",      // MIUI gallery cache
            "Android/data/com.google.android.apps.photos/cache",
            "Android/data/com.whatsapp/cache",
            "Android/data/com.facebook.katana/cache",
            "Android/data/com.instagram.android/cache",
            "Android/data/com.snapchat.android/cache",
            "Android/data/com.google.android.youtube/cache",
            "Android/data/com.spotify.music/cache",
            "DCIM/.thumbnails",
            "Pictures/.thumbnails",
            ".nomedia_trash",
            "tmp", "temp"
        ).forEach { trashPaths.add(File(extRoot, it)) }

        // 2. Internal app temp / cache dirs we own
        trashPaths.add(cacheDir)
        trashPaths.add(externalCacheDir ?: File(""))

        // 3. Common file manager trash dirs on SD card
        try {
            getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                trashPaths.add(File(dir.parent ?: "", ".Trash"))
                trashPaths.add(File(dir.parent ?: "", "temp"))
            }
        } catch (_: Exception) {}

        // Wipe each
        for (path in trashPaths) {
            try {
                if (path.exists()) freed += deleteDir(path)
            } catch (_: Exception) {}
        }

        freed
    }

    /** Recursively delete contents of a directory (keeps the dir itself). Returns bytes freed. */
    private fun deleteDir(dir: File): Long {
        var freed = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                freed += if (child.isDirectory) deleteDir(child).also { child.delete() }
                         else child.length().also { child.delete() }
            }
        } else if (dir.isFile) {
            freed = dir.length()
            dir.delete()
        }
        return freed
    }

    // ── Cache measurement ─────────────────────────────────────────────────────

    private suspend fun measureTotalCache(packages: List<String>): Long =
        withContext(Dispatchers.IO) {
            var total = 0L
            for (pkg in packages) {
                try {
                    val d = File("/data/data/$pkg/cache")
                    if (d.exists()) total += dirSize(d)
                } catch (_: Exception) {}
            }
            total
        }

    private suspend fun measureTrashSize(): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val extRoot = Environment.getExternalStorageDirectory()
        listOf(".Trash", ".trash", ".Recycle", "LOST.DIR", ".thumbnails",
               "DCIM/.thumbnails", "tmp", "temp").forEach {
            try {
                val f = File(extRoot, it)
                if (f.exists()) total += dirSize(f)
            } catch (_: Exception) {}
        }
        total
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) size += f.length() }
        return size
    }

    // ── Background app killer ─────────────────────────────────────────────────

    private suspend fun killAllBackgroundApps(): Int = withContext(Dispatchers.IO) {
        var killed = 0
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA).forEach { pkg ->
                if (pkg.packageName != packageName &&
                    pkg.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                    try { am.killBackgroundProcesses(pkg.packageName); killed++ }
                    catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        killed
    }

    private fun getInstalledApps(): List<String> =
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.packageName != packageName }
            .map { it.packageName }

    fun openAppStorageSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Accessibility ─────────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage(
                "Required to auto-tap 'Clear Cache' for each app.\n\n" +
                "1. Tap 'Open Settings'\n" +
                "2. Find 'Cleaner Service'\n" +
                "3. Toggle ON → Allow\n" +
                "4. Come back and tap CLEAN NOW!"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

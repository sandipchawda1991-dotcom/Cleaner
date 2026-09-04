package com.cleaner.cachekiller

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CacheClearService : AccessibilityService() {

    companion object {
        var appPackages: MutableList<String> = mutableListOf()
        var totalApps: Int = 0
        var currentIndex: Int = 0
        var progressCallback: ((Int, Int) -> Unit)? = null
        var doneCallback: (() -> Unit)? = null
        var instance: CacheClearService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || appPackages.isEmpty()) return

        // Only act on window state changes (new screen opened)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val rootNode = rootInActiveWindow ?: return

        // Try to find and click "Clear Cache" button
        if (tryClearCache(rootNode)) {
            // Successfully clicked clear cache, move to next app
            currentIndex++
            progressCallback?.invoke(currentIndex, totalApps)

            if (currentIndex < appPackages.size) {
                // Open next app's settings after short delay
                postDelayed({
                    openNextApp()
                }, 800)
            } else {
                // All done! Go back to our app
                postDelayed({
                    appPackages.clear()
                    doneCallback?.invoke()
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                }, 500)
            }
        }
    }

    private fun tryClearCache(node: AccessibilityNodeInfo): Boolean {
        // Labels used by different Android versions / manufacturers
        val clearCacheLabels = listOf(
            "Clear Cache", "CLEAR CACHE", "Clear cache",
            "清除缓存", "Kešatlan tozalash", "Limpiar caché",
            "Effacer le cache", "Cache löschen", "Vider le cache"
        )

        for (label in clearCacheLabels) {
            val nodes = node.findAccessibilityNodeInfosByText(label)
            for (n in nodes) {
                if (n.isEnabled && n.isClickable) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                // Sometimes the parent is clickable
                val parent = n.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        // Also try finding by view ID (works on stock Android)
        val clearCacheButton = node.findAccessibilityNodeInfosByViewId(
            "com.android.settings:id/right_button"
        )
        for (btn in clearCacheButton) {
            if (btn.isEnabled) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        return false
    }

    private fun openNextApp() {
        if (currentIndex < appPackages.size) {
            val pkg = appPackages[currentIndex]
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$pkg")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                // Skip this app and move to next
                currentIndex++
                openNextApp()
            }
        }
    }

    private fun postDelayed(action: () -> Unit, delayMs: Long) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMs)
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}

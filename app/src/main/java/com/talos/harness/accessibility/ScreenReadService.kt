package com.talos.harness.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only bridge. It keeps only transient focus metadata; screen text is returned to the caller. */
class ScreenReadService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        active = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        // Keep only the currently focused package/window, never event text or node contents.
        focusedPackage = packageName
        activeWindowId = event.windowId
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            active = false
            focusedPackage = null
            activeWindowId = -1
        }
        super.onDestroy()
    }

    private data class VisibleNode(val text: String, val type: String, val bounds: String)

    private suspend fun snapshot(allowedPackages: Set<String>, expectedWindowId: Int): Pair<String?, List<VisibleNode>>? = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow ?: return@withContext null
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        try {
            val packageName = root.packageName?.toString() ?: return@withContext null
            // Check authorization before traversing any node so out-of-scope data is never read.
            if (packageName !in allowedPackages) return@withContext packageName to emptyList()
            if (root.windowId != expectedWindowId) return@withContext null
            val rows = mutableListOf<VisibleNode>()
            pending.add(root to 0)
            var visited = 0
            while (pending.isNotEmpty() && visited < MAX_NODES && rows.size < MAX_TEXT_NODES) {
                val (node, depth) = pending.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val text = sequenceOf(node.text, node.contentDescription)
                            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                            .distinct().joinToString(" ").take(MAX_NODE_CHARS)
                        if (text.isNotEmpty()) {
                            val bounds = Rect().also(node::getBoundsInScreen)
                            rows += VisibleNode(text, node.className?.toString().orEmpty(), "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                        }
                        if (depth < MAX_DEPTH) for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { pending.add(it to depth + 1) }
                        }
                    }
                } finally {
                    if (node !== root) node.recycle()
                }
            }
            packageName to rows
        } finally {
            pending.forEach { (node, _) -> if (node !== root) node.recycle() }
            root.recycle()
        }
    }

    companion object {
        @Volatile private var instance: ScreenReadService? = null
        @Volatile private var active = false
        @Volatile private var focusedPackage: String? = null
        @Volatile private var activeWindowId: Int = -1

        const val MAX_NODES = 500
        const val MAX_TEXT_NODES = 250
        const val MAX_NODE_CHARS = 500
        const val MAX_DEPTH = 32

        fun isActive(): Boolean = active && instance != null

        /** Reads one bounded snapshot on the main thread and never stores it. */
        suspend fun readVisibleNodes(allowedPackages: Set<String>): ScreenReadResult {
            val service = instance ?: return ScreenReadResult.Unavailable
            val focus = focusedPackage ?: return ScreenReadResult.Unavailable
            if (focus !in allowedPackages) return ScreenReadResult.Unauthorized(focus)
            val window = activeWindowId
            val (packageName, nodes) = service.snapshot(allowedPackages, window) ?: return ScreenReadResult.Unavailable
            if (packageName !in allowedPackages) return ScreenReadResult.Unauthorized(packageName.orEmpty())
            // The live root must still match the event metadata to avoid returning stale/other-window data.
            if (packageName != focus || window < 0) return ScreenReadResult.Unavailable
            return ScreenReadResult.Available(ScreenSnapshot(packageName, window, nodes.map { ScreenNode(it.text, it.type, it.bounds) }))
        }
    }
}

data class ScreenSnapshot(val packageName: String, val windowId: Int, val nodes: List<ScreenNode>)
data class ScreenNode(val text: String, val type: String, val bounds: String)
sealed interface ScreenReadResult {
    data object Unavailable : ScreenReadResult
    data class Unauthorized(val packageName: String) : ScreenReadResult
    data class Available(val snapshot: ScreenSnapshot) : ScreenReadResult
}

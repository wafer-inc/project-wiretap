package com.wiretap

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/** Helper methods for creating the android accessibility info extra. */
class AccessibilityTreeCreator() {
    private val TAG = "ProjectWiretap"
    fun buildForest(windowInfos: List<AccessibilityWindowInfo>, treeType: String): String {
        val sourcesMap: ConcurrentHashMap<String, AccessibilityNodeInfo> =
            ConcurrentHashMap<String, AccessibilityNodeInfo>()
        val windows: List<String> = processWindowsAndBlock(windowInfos, sourcesMap, treeType)

        return StringBuilder().apply {
            append(windows.joinToString("\n") { window ->
                "windows {\n$window\n}"
            })
        }.toString()
    }

    private fun processWindowsAndBlock(
        windowInfos: List<AccessibilityWindowInfo>,
        sourcesMap: ConcurrentHashMap<String, AccessibilityNodeInfo>,
        treeType: String
    ): List<String> {
        val windows: List<String>
        runBlocking { windows = processWindows(windowInfos, sourcesMap, treeType) }
        return windows
    }

    private suspend fun processWindows(
        windowInfos: List<AccessibilityWindowInfo>,
        sourcesMap: ConcurrentHashMap<String, AccessibilityNodeInfo>,
        treeType: String
    ): List<String> {
        val windowInfoProtos = mutableListOf<String>()
        for (i in windowInfos.size - 1 downTo 0) {
            val window = windowInfos[i]
            val bounds = Rect()
            window.getBoundsInScreen(bounds)

            if (bounds.left == 0 && bounds.top == 0) {
                val windowInfoProto = processWindow(window, sourcesMap, treeType)
                windowInfoProto?.let { windowInfoProtos.add(windowInfoProto) }
            }
        }
        return windowInfoProtos.toList()
    }

    private fun processWindow(
        windowInfo: AccessibilityWindowInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,
        treeType: String
    ): String? {
        val bounds = Rect()
        windowInfo.getBoundsInScreen(bounds)
        val root: AccessibilityNodeInfo? = windowInfo.root

        return StringBuilder().apply {
            append("  bounds_in_screen {\n")
            append("    left: ${bounds.left}\n")
            append("    top: ${bounds.top}\n")
            append("    right: ${bounds.right}\n")
            append("    bottom: ${bounds.bottom}\n")
            append("  }\n")
            append("  is_active: ${windowInfo.isActive}\n")
            append("  id: ${windowInfo.id}\n")
            append("  layer: ${windowInfo.layer}\n")
            append("  is_accessibility_focused: ${windowInfo.isAccessibilityFocused}\n")
            append("  is_focused: ${windowInfo.isFocused}\n")
            append("  window_type: ${toWindowType(windowInfo.type)}\n")

            if (root != null) {
                runBlocking {
                    // Add if statement once we have the variable for this
                    val tree = async {
                        if (treeType == "dfs") {
                            processNodesInWindowDFS(root, sources)
                        } else {
                            processNodesInWindowBFS(root, sources)
                        }
                    }

                    append("  tree {\n")
                    append(tree.await())
                    append("  }\n")
                }
            } else {
                append("  tree {\n")
                append("    nodes {}\n")
                append("  }\n")
            }
        }.toString()
    }

    private suspend fun processNodesInWindowDFS(
        root: AccessibilityNodeInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,
    ): String {
        val uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo> = UniqueIdsGenerator()
        val seenNodes: HashSet<AccessibilityNodeInfo> = HashSet()
        val nodesList = mutableListOf<String>()

        suspend fun processNodeDfs(
            nodePair: ParentChildNodePair,
            depth: Int
        ) {
            if (seenNodes.contains(nodePair.child)) {
                return
            }

            seenNodes.add(nodePair.child)

            val nodeString = processNode(nodePair, sources, uniqueIdsCache, depth)
            nodesList.add(nodeString)

            for (i in 0 until nodePair.child.childCount) {
                val childNode: AccessibilityNodeInfo? = nodePair.child.getChild(i)
                if (childNode != null) {
                    val childPair = ParentChildNodePair.builder()
                        .child(childNode)
                        .parent(nodePair.child)
                        .build()
                    processNodeDfs(childPair, depth + 1)
                }
            }
        }

        runBlocking {
            val rootPair = ParentChildNodePair.builder()
                .child(root)
                .build()
            processNodeDfs(rootPair, 0)
        }

        return nodesList.joinToString(separator = "\n") { "    nodes {\n$it    }" }
    }

    private suspend fun processNodesInWindowBFS(
        root: AccessibilityNodeInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,
    ): String {
        val traversalQueue = ArrayDeque<ParentChildNodePair>()
        traversalQueue.add(ParentChildNodePair.builder().child(root).build())
        val uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo> = UniqueIdsGenerator()
        val nodesDeferred = mutableListOf<Deferred<String>>()
        val seenNodes: HashSet<AccessibilityNodeInfo> = HashSet()
        seenNodes.add(root)
        val nodesList = mutableListOf<String>()

        runBlocking {
            var currentDepth = 0
            while (traversalQueue.isNotEmpty()) {
                for (nodesAtCurrentDepth in traversalQueue.size downTo 1) {
                    val nodePair: ParentChildNodePair = traversalQueue.removeFirst()
                    for (i in 0 until nodePair.child.childCount) {
                        val childNode: AccessibilityNodeInfo? = nodePair.child.getChild(i)
                        if (childNode != null && !seenNodes.contains(childNode)) {
                            traversalQueue.add(
                                ParentChildNodePair.builder()
                                    .child(childNode)
                                    .parent(nodePair.child)
                                    .build()
                            )
                            seenNodes.add(childNode)
                        }
                    }
                    val thisDepth = currentDepth
                    val deferred = async {
                        processNode(nodePair, sources, uniqueIdsCache, thisDepth)
                    }
                    nodesDeferred.add(deferred)
                }
                currentDepth++
            }
            nodesList.addAll(nodesDeferred.awaitAll())
        }

        return nodesList.joinToString(separator = "\n") { "    nodes {\n$it    }" }
    }

    private fun processNode(
        nodePair: ParentChildNodePair,
        sourceBuilder: ConcurrentHashMap<String, AccessibilityNodeInfo>,
        uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo>,
        nodeDepth: Int,
    ): String {
        val node: AccessibilityNodeInfo = nodePair.child
        val nodeString: String = createAndroidAccessibilityNode(
            node,
            uniqueIdsCache.getUniqueId(node),
            nodeDepth,
            getChildUniqueIds(node, uniqueIdsCache),
        )
        sourceBuilder[nodeString] = node
        return nodeString
    }

    private fun createAndroidAccessibilityNode(
        node: AccessibilityNodeInfo,
        nodeId: Int,
        depth: Int,
        childIds: List<Int>
    ): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return StringBuilder().apply {
            append("      bounds_in_screen {\n")
            append("        left: ${bounds.left}\n")
            append("        top: ${bounds.top}\n")
            append("        right: ${bounds.right}\n")
            append("        bottom: ${bounds.bottom}\n")
            append("      }\n")
            append("      is_checkable: ${node.isCheckable}\n")
            append("      is_checked: ${node.isChecked}\n")
            append("      class_name: \"${escapeString(stringFromNullableCharSequence(node.className))}\"\n")
            append("      is_clickable: ${node.isClickable}\n")
            append("      content_description: \"${escapeString(stringFromNullableCharSequence(node.contentDescription))}\"\n")
            append("      is_editable: ${node.isEditable}\n")
            append("      is_enabled: ${node.isEnabled}\n")
            append("      is_focusable: ${node.isFocusable}\n")
            append("      hint_text: \"${escapeString(stringFromNullableCharSequence(node.hintText))}\"\n")
            append("      is_long_clickable: ${node.isLongClickable}\n")
            append("      package_name: \"${escapeString(stringFromNullableCharSequence(node.packageName))}\"\n")
            append("      is_password: ${node.isPassword}\n")
            append("      is_scrollable: ${node.isScrollable}\n")
            append("      is_selected: ${node.isSelected}\n")
            append("      text: \"${escapeString(stringFromNullableCharSequence(node.text))}\"\n")
            append("      text_selection_end: ${node.textSelectionEnd}\n")
            append("      text_selection_start: ${node.textSelectionStart}\n")
            append("      view_id_resource_name: \"${escapeString(node.viewIdResourceName ?: "")}\"\n")
            append("      is_visible_to_user: ${node.isVisibleToUser}\n")
            append("      window_id: ${node.windowId}\n")
            append("      unique_id: $nodeId\n")
            append("      drawing_order: ${node.drawingOrder}\n")
            append("      tooltip_text: \"${escapeString(stringFromNullableCharSequence(node.tooltipText))}\"\n")
            append("      depth: $depth\n")

            node.actionList?.forEach { action ->
                append("      actions {\n")
                append("        id: ${action.id}\n")
                append("        label: \"${escapeString(stringFromNullableCharSequence(action.label))}\"\n")
                append("      }\n")
            }

            childIds.forEach { childId ->
                append("      child_ids: $childId\n")
            }
        }.toString()
    }
}

private fun getChildUniqueIds(
    node: AccessibilityNodeInfo,
    uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo>,
): List<Int> {
    val ids = mutableListOf<Int>()
    for (childId in 0 until node.childCount) {
        val child: AccessibilityNodeInfo = node.getChild(childId) ?: continue
        ids.add(uniqueIdsCache.getUniqueId(child))
    }
    return ids.toList()
}

fun stringFromNullableCharSequence(cs: CharSequence?): String = cs?.toString() ?: ""

private fun toWindowType(type: Int): String =
    when (type) {
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
        AccessibilityWindowInfo.TYPE_APPLICATION -> "TYPE_APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "TYPE_INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "TYPE_SYSTEM"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "TYPE_SPLIT_SCREEN_DIVIDER"
        else -> "UNKNOWN_TYPE"
    }

private fun escapeString(input: String?): String {
    return input?.replace("\n", "\\n")?.replace("\"", "\\\"") ?: ""
}

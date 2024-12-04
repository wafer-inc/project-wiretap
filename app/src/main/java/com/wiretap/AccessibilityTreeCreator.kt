// Copyright 2024 DeepMind Technologies Limited.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.wiretap

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.collections.mutableListOf
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/** Helper methods for creating the android accessibility info extra. */
class AccessibilityTreeCreator() {
    private val TAG = "ProjectWiretap"

    fun buildForest(windowInfos: List<AccessibilityWindowInfo>): String {
        val sourcesMap: ConcurrentHashMap<String, AccessibilityNodeInfo> =
            ConcurrentHashMap<String, AccessibilityNodeInfo>()
        val windows: List<String> = processWindowsAndBlock(windowInfos, sourcesMap)

        return StringBuilder().apply {
            append("windows {\n")
            append(windows.joinToString("\n"))
            append("\n}")
        }.toString()
    }

    private fun processWindowsAndBlock(
        windowInfos: List<AccessibilityWindowInfo>,
        sourcesMap: ConcurrentHashMap<String, AccessibilityNodeInfo>,
    ): List<String> {
        val windows: List<String>
        runBlocking { windows = processWindows(windowInfos, sourcesMap) }
        return windows
    }

    private suspend fun processWindows(
        windowInfos: List<AccessibilityWindowInfo>,
        sourcesMap: ConcurrentHashMap<String, AccessibilityNodeInfo>,
    ): List<String> {
        var windowInfoProtos = mutableListOf<String>()
        for (i in windowInfos.size - 1 downTo 0) {
            val windowInfoProto = processWindow(windowInfos.get(i), sourcesMap)
            windowInfoProto?.let { windowInfoProtos.add(windowInfoProto) }
        }
        return windowInfoProtos.toList()
    }

    private fun processWindow(
        windowInfo: AccessibilityWindowInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,
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
                val treeDeferred: Deferred<String>
                runBlocking {
                    treeDeferred = async { processNodesInWindow(root, sources) }
                    append("  tree {\n")
                    append(treeDeferred.await())
                    append("  }\n")
                }
            } else {
                append("  tree {\n")
                append("    nodes {}\n")
                append("  }\n")
            }
        }.toString()
    }

    private suspend fun processNodesInWindow(
        root: AccessibilityNodeInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,
    ): String {
        val traversalQueue = ArrayDeque<ParentChildNodePair>()
        traversalQueue.add(ParentChildNodePair.builder().child(root).build())
        val uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo> = UniqueIdsGenerator()
        var currentDepth = 0
        val nodesDeferred = mutableListOf<Deferred<String>>()
        val seenNodes: HashSet<AccessibilityNodeInfo> = HashSet()
        seenNodes.add(root)
        val nodesList = mutableListOf<String>()

        runBlocking {
            while (!traversalQueue.isEmpty()) {
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

        return StringBuilder().apply {
            for (node in nodesList) {
                append("    nodes {\n")
                append(node)
                append("    }\n")
            }
        }.toString()
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
        sourceBuilder.put(nodeString, node)
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
            append("      class_name: \"${stringFromNullableCharSequence(node.className)}\"\n")
            append("      is_clickable: ${node.isClickable}\n")
            append("      content_description: \"${stringFromNullableCharSequence(node.contentDescription)}\"\n")
            append("      is_editable: ${node.isEditable}\n")
            append("      is_enabled: ${node.isEnabled}\n")
            append("      is_focusable: ${node.isFocusable}\n")
            append("      hint_text: \"${stringFromNullableCharSequence(node.hintText)}\"\n")
            append("      is_long_clickable: ${node.isLongClickable}\n")
            append("      package_name: \"${stringFromNullableCharSequence(node.packageName)}\"\n")
            append("      is_password: ${node.isPassword}\n")
            append("      is_scrollable: ${node.isScrollable}\n")
            append("      is_selected: ${node.isSelected}\n")
            append("      text: \"${stringFromNullableCharSequence(node.text)}\"\n")
            append("      text_selection_end: ${node.textSelectionEnd}\n")
            append("      text_selection_start: ${node.textSelectionStart}\n")
            append("      view_id_resource_name: \"${node.viewIdResourceName ?: ""}\"\n")
            append("      is_visible_to_user: ${node.isVisibleToUser}\n")
            append("      window_id: ${node.windowId}\n")
            append("      unique_id: $nodeId\n")
            append("      drawing_order: ${node.drawingOrder}\n")
            append("      tooltip_text: \"${stringFromNullableCharSequence(node.tooltipText)}\"\n")
            append("      depth: $depth\n")

            // Process actions
            node.actionList?.forEach { action ->
                append("      actions {\n")
                append("        id: ${action.id}\n")
                append("        label: \"${stringFromNullableCharSequence(action.label)}\"\n")
                append("      }\n")
            }

            // Process child IDs
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
    for (childId in 0 until node.getChildCount()) {
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

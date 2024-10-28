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
            append("{\n")
            append("""  "windows": [""").append("\n")
            append(windows.joinToString(",\n"))
            append("\n  ]\n")
            append("}")
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

    private suspend fun processWindow(
        windowInfo: AccessibilityWindowInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,
    ): String? {
        val bounds = Rect()
        windowInfo.getBoundsInScreen(bounds)
        val root: AccessibilityNodeInfo? = windowInfo.root

        return StringBuilder().apply {
            append("{\n")
            if (root == null) {
                Log.i(TAG, "window root is null")
                append("""  "tree": { "nodes": [] }""").append(",\n")
            } else {
                val treeDeferred: Deferred<String>
                runBlocking {
                    treeDeferred = async { processNodesInWindow(root, sources) }
                    append("""  "tree": ${treeDeferred.await()}""").append(",\n")
                }
            }

            append("""  "isActive": ${windowInfo.isActive}""").append(",\n")
            append("""  "id": ${windowInfo.id}""").append(",\n")
            append("""  "layer": ${windowInfo.layer}""").append(",\n")
            append("""  "isAccessibilityFocused": ${windowInfo.isAccessibilityFocused}""").append(",\n")
            append("""  "isFocused": ${windowInfo.isFocused}""").append(",\n")
            append("""  "boundsInScreen": {""").append("\n")
            append("""    "left": ${bounds.left}""").append(",\n")
            append("""    "top": ${bounds.top}""").append(",\n")
            append("""    "right": ${bounds.right}""").append(",\n")
            append("""    "bottom": ${bounds.bottom}""").append("\n")
            append("  }").append(",\n")
            append("""  "windowType": "${toWindowType(windowInfo.type)}" """).append("\n")
            append("}")
        }.toString()
    }

    private suspend fun processNodesInWindow(
        root: AccessibilityNodeInfo,
        sources: ConcurrentHashMap<String, AccessibilityNodeInfo>,  // Changed key type to String
    ): String {  // Changed return type to String
        Log.d(TAG, "processNodesInWindow()")
        val traversalQueue = ArrayDeque<ParentChildNodePair>()
        traversalQueue.add(ParentChildNodePair.builder().child(root).build())
        val uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo> = UniqueIdsGenerator()
        var currentDepth = 0
        val nodesDeferred = mutableListOf<Deferred<String>>()  // Changed to String
        val seenNodes: HashSet<AccessibilityNodeInfo> = HashSet()
        seenNodes.add(root)
        val nodesList = mutableListOf<String>()  // To collect all nodes

        runBlocking {
            while (!traversalQueue.isEmpty()) {
                // Traverse the tree layer-by-layer.
                // The first layer has only the root and depth 0.
                // The second layer has all the root's children and depth 1.
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

            // Collect all nodes and build the final JSON array
            nodesList.addAll(nodesDeferred.awaitAll())
        }

        // Return a JSON array of all nodes
        return StringBuilder().apply {
            append("{\n")
            append("""  "nodes": [""").append("\n")
            append(nodesList.joinToString(",\n"))
            append("\n  ]\n")
            append("}")
        }.toString()
    }
}

private fun processNode(
    nodePair: ParentChildNodePair,
    sourceBuilder: ConcurrentHashMap<String, AccessibilityNodeInfo>,  // Changed key type to String
    uniqueIdsCache: UniqueIdsGenerator<AccessibilityNodeInfo>,
    nodeDepth: Int,
): String {  // Changed return type to String
    val node: AccessibilityNodeInfo = nodePair.child
    val nodeString: String = createAndroidAccessibilityNode(
        node,
        uniqueIdsCache.getUniqueId(node),
        nodeDepth,
        getChildUniqueIds(node, uniqueIdsCache),
    )
    sourceBuilder.put(nodeString, node)  // Store the string representation as key
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
    val actions = node.getActionList().stream()
        .map { action -> """{"id": ${action.id}, "label": "${stringFromNullableCharSequence(action.label)}"}""" }
        .collect(Collectors.joining(", "))

    return StringBuilder().apply {
        append("{\n")
        append("""  "actions": [$actions],""").append("\n")
        append("""  "boundsInScreen": {""").append("\n")
        append("""    "left": ${bounds.left},""").append("\n")
        append("""    "top": ${bounds.top},""").append("\n")
        append("""    "right": ${bounds.right},""").append("\n")
        append("""    "bottom": ${bounds.bottom}""").append("\n")
        append("  },\n")
        append("""  "isCheckable": ${node.isCheckable},""").append("\n")
        append("""  "isChecked": ${node.isChecked},""").append("\n")
        append("""  "className": "${stringFromNullableCharSequence(node.getClassName())}",""").append("\n")
        append("""  "isClickable": ${node.isClickable},""").append("\n")
        append("""  "contentDescription": "${stringFromNullableCharSequence(node.getContentDescription())}",""").append("\n")
        append("""  "isEditable": ${node.isEditable},""").append("\n")
        append("""  "isEnabled": ${node.isEnabled},""").append("\n")
        append("""  "isFocusable": ${node.isFocusable},""").append("\n")
        append("""  "hintText": "${stringFromNullableCharSequence(node.getHintText())}",""").append("\n")
        append("""  "isLongClickable": ${node.isLongClickable},""").append("\n")
        append("""  "packageName": "${stringFromNullableCharSequence(node.getPackageName())}",""").append("\n")
        append("""  "isPassword": ${node.isPassword},""").append("\n")
        append("""  "isScrollable": ${node.isScrollable},""").append("\n")
        append("""  "isSelected": ${node.isSelected},""").append("\n")
        append("""  "text": "${stringFromNullableCharSequence(node.getText())}",""").append("\n")
        append("""  "textSelectionEnd": ${node.getTextSelectionEnd()},""").append("\n")
        append("""  "textSelectionStart": ${node.getTextSelectionStart()},""").append("\n")
        append("""  "viewIdResourceName": "${node.getViewIdResourceName() ?: ""}",""").append("\n")
        append("""  "isVisibleToUser": ${node.isVisibleToUser},""").append("\n")
        append("""  "windowId": ${node.windowId},""").append("\n")
        append("""  "uniqueId": $nodeId,""").append("\n")
        append("""  "childIds": [${childIds.joinToString(", ")}],""").append("\n")
        append("""  "drawingOrder": ${node.drawingOrder},""").append("\n")
        append("""  "tooltipText": "${stringFromNullableCharSequence(node.getTooltipText())}",""").append("\n")
        append("""  "depth": $depth""").append("\n")
        append("}")
    }.toString()
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

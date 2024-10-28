
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

import android.view.accessibility.AccessibilityNodeInfo

/** Parent and child [AccessibilityNodeInfo] relationship. */
internal data class ParentChildNodePair private constructor(
    val parent: AccessibilityNodeInfo?,
    val child: AccessibilityNodeInfo
) {
    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var parent: AccessibilityNodeInfo? = null
        private var child: AccessibilityNodeInfo? = null

        fun parent(parent: AccessibilityNodeInfo?) = apply {
            this.parent = parent
        }

        fun child(child: AccessibilityNodeInfo) = apply {
            this.child = child
        }

        fun build(): ParentChildNodePair {
            val childNode = child
            requireNotNull(childNode) { "Child AccessibilityNodeInfo cannot be null" }
            return ParentChildNodePair(parent, childNode)
        }
    }
}
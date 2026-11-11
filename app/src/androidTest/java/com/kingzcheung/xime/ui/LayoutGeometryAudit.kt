package com.kingzcheung.xime.ui

import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import com.kingzcheung.xime.ui.keyboard.ControlShape
import com.kingzcheung.xime.ui.keyboard.RenderedControlShape

/** Inspect rendered text, not only its container. Scroll viewports may clip at their edges;
 * a text's own measured height must still contain every rendered line. */
internal fun ComposeContentTestRule.geometryIssues(rootTag: String, allowViewportClipping: Boolean = true): List<String> {
    val root = onNodeWithTag(rootTag, useUnmergedTree = true).fetchSemanticsNode()
    val issues = mutableListOf<String>()
    data class Action(val label: String, val rect: androidx.compose.ui.geometry.Rect, val region: android.graphics.Region)
    val actions = mutableListOf<Action>()
    fun containsAction(node: SemanticsNode): Boolean = node.config.contains(SemanticsActions.OnClick) ||
        (!node.config.isClearingSemantics && node.children.any(::containsAction))
    fun labelled(node: SemanticsNode): Boolean = node.config.contains(SemanticsProperties.TestTag) ||
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotEmpty() } == true ||
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotEmpty() } == true ||
        (!node.config.isClearingSemantics && node.children.any(::labelled))
    fun shape(node: SemanticsNode): RenderedControlShape? = node.config.getOrNull(ControlShape) ?: node.children.firstNotNullOfOrNull { shape(it) }
    fun region(node: SemanticsNode): android.graphics.Region {
        val bounds = node.boundsInRoot
        val box = android.graphics.Region(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        val rendered = shape(node) ?: return box
        val path = when (val outline = rendered.outline) {
            is Outline.Generic -> outline.path
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        }
        val translated = android.graphics.Path(path.asAndroidPath()).apply {
            offset(bounds.center.x - rendered.size.width / 2, bounds.center.y - rendered.size.height / 2)
        }
        return android.graphics.Region().apply { setPath(translated, box) }
    }
    fun walk(node: SemanticsNode, scrolling: Boolean, actionsHidden: Boolean = false) {
        val config = node.config
        val label = config.getOrNull(SemanticsProperties.TestTag)
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: "node-${node.id}"
        val bounds = node.boundsInRoot
        val hasScroll = allowViewportClipping && (scrolling || config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
            config.contains(SemanticsProperties.HorizontalScrollAxisRange))
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        val textResults = mutableListOf<TextLayoutResult>()
        if (action != null) action(textResults)
        textResults.filter { it.layoutInput.text.isNotEmpty() }.forEach { text ->
            if (text.lineCount > 0 && text.getLineBottom(text.lineCount - 1) > text.size.height + 1f) {
                issues += "$label: 文字高度 ${text.getLineBottom(text.lineCount - 1)} > 分配高度 ${text.size.height}; bounds=$bounds"
            }
            if (!hasScroll && (bounds.height + 1 < text.size.height || bounds.width + 1 < text.size.width)) {
                issues += "$label: 文字被父容器裁切，文字=${text.size} 可见=$bounds"
            }
        }
        if (!hasScroll && (textResults.any { it.layoutInput.text.isNotEmpty() } || config.contains(SemanticsActions.OnClick))) {
            if (bounds.width <= 0 || bounds.height <= 0) issues += "$label: 可操作/文字元素尺寸为零 $bounds"
            val viewport = root.boundsInRoot
            if (bounds.top < viewport.top - 1 || bounds.bottom > viewport.bottom + 1 ||
                bounds.left < viewport.left - 1 || bounds.right > viewport.right + 1) {
                issues += "$label: 元素越出面板 $bounds / $viewport"
            }
            // Unlabelled sibling click barriers are intentional modal backdrops, not controls.
            if (!actionsHidden && config.contains(SemanticsActions.OnClick) && labelled(node) &&
                (config.isClearingSemantics || node.children.none(::containsAction))) actions += Action(label, bounds, region(node))
        }
        // Unmerged children remain available for text-layout auditing, but clearAndSetSemantics
        // hides their actions (e.g. the keyboard underneath an active tool overlay).
        node.children.forEach { walk(it, hasScroll, actionsHidden || config.isClearingSemantics) }
    }
    runOnIdle { walk(root, false) }
    actions.forEachIndexed { i, a -> actions.drop(i + 1).forEach { b ->
        val overlap = android.graphics.Region(a.region).apply { op(b.region, android.graphics.Region.Op.INTERSECT) }
        if (!overlap.isEmpty && overlap.bounds.width() > 1 && overlap.bounds.height() > 1) issues += "${a.label} / ${b.label}: 操作区域相互遮挡 ${a.rect} / ${b.rect}"
    } }
    return issues
}

internal fun ComposeContentTestRule.assertGeometry(rootTag: String, scenario: String) {
    val issues = geometryIssues(rootTag)
    org.junit.Assert.assertTrue("$scenario\n${issues.joinToString("\n")}", issues.isEmpty())
}

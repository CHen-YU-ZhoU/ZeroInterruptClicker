package com.zerointerrupt.clicker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.HorizontalRuler
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxBy
import androidx.compose.ui.util.fastSumBy
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Material 3 Expressive FAB Menu ported from official androidx.compose.material3 FloatingActionButtonMenu.kt
 * with official ExpressiveMotionTokens & FabMenuBaselineTokens (56dp capsules, 4dp tight vertical spacing,
 * bottom-to-top stagger + right-anchored spatial body stretch).
 */
private object M3FabMenuTokens {
    val ListItemBetweenSpace = 4.dp
    val CloseButtonBetweenSpace = 8.dp
    val ListItemContainerHeight = 54.dp
    val ListItemMinWidth = 56.dp
    val ListItemLeadingSpace = 22.dp
    val ListItemTrailingSpace = 22.dp
    val ListItemIconLabelSpace = 10.dp
    val ButtonPaddingBottom = 16.dp
    val MenuPaddingHorizontal = 16.dp

    // Official ExpressiveMotionTokens
    fun fastSpatialFloat(): FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.6f, stiffness = 800f)

    fun fastEffectsFloat(): FiniteAnimationSpec<Float> =
        spring(dampingRatio = 1.0f, stiffness = 3800f)

    fun slowEffectsInt(): FiniteAnimationSpec<Int> =
        spring(dampingRatio = 1.0f, stiffness = 800f, visibilityThreshold = 1)
}

interface M3FabMenuScope {
    val horizontalAlignment: Alignment.Horizontal
}

@Composable
fun ExpressiveFloatingActionButtonMenu(
    expanded: Boolean,
    button: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    content: @Composable M3FabMenuScope.() -> Unit,
) {
    var buttonHeight by remember { mutableIntStateOf(0) }

    Layout(
        modifier = modifier.padding(horizontal = M3FabMenuTokens.MenuPaddingHorizontal),
        content = {
            ExpressiveFabMenuItemColumn(
                modifier = Modifier,
                expanded = expanded,
                horizontalAlignment = horizontalAlignment,
                buttonHeight = { buttonHeight },
                content = content,
            )
            Box { button() }
        },
    ) { measurables, constraints ->
        val menuItemsPlaceable = measurables[0].measure(constraints)
        val buttonPaddingBottom = M3FabMenuTokens.ButtonPaddingBottom.roundToPx()
        var buttonPlaceable: Placeable? = null
        val suggestedWidth: Int
        val suggestedHeight: Int
        if (measurables.size > 1) {
            buttonPlaceable = measurables[1].measure(constraints)
            buttonHeight = buttonPlaceable.height
            suggestedWidth = maxOf(buttonPlaceable.width, menuItemsPlaceable.width)
            suggestedHeight = maxOf(buttonPlaceable.height + buttonPaddingBottom, menuItemsPlaceable.height)
        } else {
            suggestedWidth = menuItemsPlaceable.width
            suggestedHeight = menuItemsPlaceable.height
        }

        val width = minOf(suggestedWidth, constraints.maxWidth)
        val height = minOf(suggestedHeight, constraints.maxHeight)

        layout(width, height) {
            val menuItemsX = horizontalAlignment.align(menuItemsPlaceable.width, width, layoutDirection)
            menuItemsPlaceable.place(menuItemsX, 0)
            if (buttonPlaceable != null) {
                val buttonX = horizontalAlignment.align(buttonPlaceable.width, width, layoutDirection)
                val buttonY = height - buttonPlaceable.height - buttonPaddingBottom
                buttonPlaceable.place(buttonX, buttonY)
            }
        }
    }
}

@Composable
private fun ExpressiveFabMenuItemColumn(
    modifier: Modifier,
    expanded: Boolean,
    horizontalAlignment: Alignment.Horizontal,
    buttonHeight: () -> Int,
    content: @Composable M3FabMenuScope.() -> Unit,
) {
    var itemCount by remember { mutableIntStateOf(0) }
    var itemsNeedVerticalScroll by remember { mutableStateOf(false) }
    var originalConstraints: Constraints? = null
    var staggerAnim by remember { mutableStateOf<Animatable<Int, AnimationVector1D>?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val staggerAnimSpec = remember { M3FabMenuTokens.slowEffectsInt() }

    Layout(
        modifier = modifier
            .clipToBounds()
            .semantics {
                isTraversalGroup = true
                traversalIndex = -0.9f
            }
            .layout { measurable, constraints ->
                originalConstraints = constraints
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .then(
                if (itemsNeedVerticalScroll) {
                    Modifier.verticalScroll(state = rememberScrollState(), enabled = expanded)
                } else {
                    Modifier
                }
            ),
        content = {
            val scope = remember(horizontalAlignment) {
                object : M3FabMenuScope {
                    override val horizontalAlignment: Alignment.Horizontal get() = horizontalAlignment
                }
            }
            content(scope)
        },
    ) { measurables, constraints ->
        itemCount = measurables.size

        val targetItemCount = if (expanded) itemCount else 0
        staggerAnim = staggerAnim?.also {
            if (it.targetValue != targetItemCount) {
                coroutineScope.launch {
                    it.animateTo(targetValue = targetItemCount, animationSpec = staggerAnimSpec)
                }
            }
        } ?: Animatable(targetItemCount, Int.VectorConverter)

        val placeables = measurables.fastMap { it.measure(constraints) }
        val width = placeables.fastMaxBy { it.width }?.width ?: 0

        val verticalSpacing = M3FabMenuTokens.ListItemBetweenSpace.roundToPx()
        val verticalSpacingHeight = if (placeables.isNotEmpty()) verticalSpacing * (placeables.size - 1) else 0
        val currentButtonHeight = buttonHeight()
        val bottomPadding = if (currentButtonHeight > 0) {
            currentButtonHeight +
                M3FabMenuTokens.ButtonPaddingBottom.roundToPx() +
                M3FabMenuTokens.CloseButtonBetweenSpace.roundToPx()
        } else 0
        val height = placeables.fastSumBy { it.height } + verticalSpacingHeight + bottomPadding
        var visibleHeight = bottomPadding.toFloat()
        placeables.fastForEachIndexed { index, placeable ->
            val itemVisible = index >= itemCount - (staggerAnim?.value ?: 0)
            if (itemVisible) {
                visibleHeight += placeable.height
                if (index < placeables.size - 1) {
                    visibleHeight += verticalSpacing
                }
            }
        }

        val finalHeight = if (placeables.fastAny { item -> item.isVisible }) height else 0
        itemsNeedVerticalScroll = finalHeight > (originalConstraints?.maxHeight ?: constraints.maxHeight)

        layout(width, finalHeight, rulers = { M3FabMenuRuler provides height - visibleHeight }) {
            var y = 0
            placeables.fastForEachIndexed { index, placeable ->
                val x = horizontalAlignment.align(placeable.width, width, layoutDirection)
                placeable.place(x, y)
                y += placeable.height
                if (index < placeables.size - 1) {
                    y += verticalSpacing
                }
            }
        }
    }
}

@Composable
fun M3FabMenuScope.ExpressiveFabMenuItem(
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(containerColor),
) {
    var widthAnim by remember { mutableStateOf<Animatable<Float, AnimationVector1D>?>(null) }
    var alphaAnim by remember { mutableStateOf<Animatable<Float, AnimationVector1D>?>(null) }
    val widthSpring = remember { M3FabMenuTokens.fastSpatialFloat() }
    val alphaSpring = remember { M3FabMenuTokens.fastEffectsFloat() }
    val coroutineScope = rememberCoroutineScope()

    var isVisible by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            modifier = modifier
                .fabItemVisible { isVisible }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        val target = if (M3FabMenuRuler.current(Float.POSITIVE_INFINITY) <= 0) 1f else 0f

                        widthAnim = widthAnim?.also {
                            if (it.targetValue != target) {
                                coroutineScope.launch { it.animateTo(target, widthSpring) }
                            }
                        } ?: Animatable(target, Float.VectorConverter)

                        val tempAlphaAnim = alphaAnim?.also {
                            if (it.targetValue != target) {
                                coroutineScope.launch { it.animateTo(target, alphaSpring) }
                            }
                        } ?: Animatable(target, Float.VectorConverter)
                        alphaAnim = tempAlphaAnim
                        isVisible = tempAlphaAnim.value != 0f
                        if (isVisible) {
                            placeable.placeWithLayer(0, 0) { alpha = tempAlphaAnim.value }
                        }
                    }
                },
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            onClick = onClick,
        ) {
            Row(
                Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (placeable.width * maxOf(widthAnim?.value ?: 0f, 0f)).roundToInt()
                        layout(width, placeable.height) {
                            val x = horizontalAlignment.align(placeable.width, width, layoutDirection)
                            placeable.placeWithLayer(x, 0)
                        }
                    }
                    .sizeIn(
                        minWidth = M3FabMenuTokens.ListItemMinWidth,
                        minHeight = M3FabMenuTokens.ListItemContainerHeight
                    )
                    .padding(
                        start = M3FabMenuTokens.ListItemLeadingSpace,
                        end = M3FabMenuTokens.ListItemTrailingSpace,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    M3FabMenuTokens.ListItemIconLabelSpace,
                    Alignment.CenterHorizontally,
                ),
            ) {
                icon()
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    content = text,
                )
            }
        }
    }
}

@Composable
fun ExpressiveToggleFab(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialSize: Dp = 48.dp,
    finalSize: Dp = 52.dp,
    initialCornerRadius: Dp = 14.dp,
    finalCornerRadius: Dp = 26.dp,
    initialContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    finalContainerColor: Color = MaterialTheme.colorScheme.primary,
    initialIconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    finalIconColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable (progress: () -> Float, iconColor: Color) -> Unit,
) {
    val checkedProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = M3FabMenuTokens.fastSpatialFloat(),
        label = "toggleFabProgress"
    )
    val progressProvider = { checkedProgress.coerceIn(0f, 1.25f) }
    val clamped = checkedProgress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val fabRippleRadius = remember(initialSize, density) {
        with(density) {
            val half = initialSize.toPx() / 2f
            hypot(half, half).toDp()
        }
    }
    val shape = remember(density, initialCornerRadius, finalCornerRadius) {
        GenericShape { size, _ ->
            val r = with(density) {
                lerp(initialCornerRadius, finalCornerRadius, clamped).toPx()
            }
            addRoundRect(RoundRect(size.toRect(), CornerRadius(r)))
        }
    }

    Box(Modifier.size(maxOf(initialSize, finalSize)), contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier
                .graphicsLayer {
                    this.shadowElevation = 6.dp.toPx()
                    this.shape = shape
                    this.clip = true
                }
                .drawBehind {
                    val c = lerp(initialContainerColor, finalContainerColor, clamped)
                    val r = with(density) { lerp(initialCornerRadius, finalCornerRadius, clamped).toPx() }
                    drawRoundRect(color = c, cornerRadius = CornerRadius(r))
                }
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    interactionSource = null,
                    indication = ripple(radius = fabRippleRadius),
                )
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val sz = lerp(initialSize, finalSize, clamped).roundToPx()
                    layout(sz, sz) {
                        placeable.place((sz - placeable.width) / 2, (sz - placeable.height) / 2)
                    }
                }
        ) {
            val iconColor = lerp(initialIconColor, finalIconColor, clamped)
            content(progressProvider, iconColor)
        }
    }
}

private val M3FabMenuRuler = HorizontalRuler()

@Stable
private fun Modifier.fabItemVisible(isVisible: () -> Boolean) =
    this then FabItemVisibleElement(isVisible = isVisible)

private class FabItemVisibleElement(private val isVisible: () -> Boolean) :
    ModifierNodeElement<FabItemVisibilityModifier>() {
    override fun create() = FabItemVisibilityModifier(isVisible)
    override fun update(node: FabItemVisibilityModifier) {
        node.visible = isVisible
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fabItemVisible"
        value = isVisible()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FabItemVisibleElement) return false
        return isVisible === other.isVisible
    }

    override fun hashCode(): Int = isVisible.hashCode()
}

private class FabItemVisibilityModifier(isVisible: () -> Boolean) :
    ParentDataModifierNode, SemanticsModifierNode, Modifier.Node() {
    var visible: () -> Boolean = isVisible
    override fun Density.modifyParentData(parentData: Any?): Any? = this@FabItemVisibilityModifier
    override val shouldClearDescendantSemantics: Boolean get() = !visible()
    override fun SemanticsPropertyReceiver.applySemantics() {}
}

private val Placeable.isVisible: Boolean
    get() = (this.parentData as? FabItemVisibilityModifier)?.visible?.invoke() != false

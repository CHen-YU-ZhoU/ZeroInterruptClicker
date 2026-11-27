package com.zerointerrupt.clicker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.ui.MainTab
import kotlin.math.PI
import kotlin.math.sin

private val EaseOutBack = CubicBezierEasing(0.34f, 1.45f, 0.64f, 1f)
private val EaseOutCubic = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val EaseInOutCubic = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/**
 * Animated Material 3 navigation icons for all 4 tabs (using a linear master timeline so every
 * multi-stage sub-animation gets crisp, unhurried frame pacing):
 * - HOME: Solid house silhouette whose central vertical door seam pulls open left & right into a doorway rectangle.
 * - PRESETS: Two full diamond sheets shown clearly; the upper sheet drops down to cover the lower sheet with real occlusion.
 * - TUNING: Three mixer slider knobs glide with staggered fine-tuning passes into solid vertical bars.
 * - LAB: Conical flask swirls gently while liquid fills up and multiple bubbles rise through the neck and pop at the rim.
 */
@Composable
fun AnimatedTabIcon(
    tab: MainTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    sizeDp: Int = 22
) {
    val tint = LocalContentColor.current
    val anim = remember { Animatable(if (selected) 1f else 0f) }
    var prevSelected by remember { mutableStateOf(selected) }

    LaunchedEffect(selected) {
        if (selected && !prevSelected) {
            anim.snapTo(0f)
            val duration = when (tab) {
                MainTab.HOME -> 700
                MainTab.PRESETS -> 780
                MainTab.TUNING -> 740
                MainTab.LAB -> 860
            }
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            )
        } else if (!selected) {
            anim.snapTo(0f)
        }
        prevSelected = selected
    }

    val progress = anim.value
    Canvas(
        modifier = modifier
            .size(sizeDp.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        val u = size.minDimension / 24f
        when (tab) {
            MainTab.HOME -> drawAnimatedHomeIcon(u, selected, progress, tint)
            MainTab.PRESETS -> drawAnimatedPresetsIcon(u, selected, progress, tint)
            MainTab.TUNING -> drawAnimatedTuningIcon(u, selected, progress, tint)
            MainTab.LAB -> drawAnimatedLabIcon(u, selected, progress, tint)
        }
    }
}

private fun DrawScope.drawAnimatedHomeIcon(
    u: Float,
    selected: Boolean,
    t: Float,
    color: Color
) {
    if (!selected) {
        val houseStroke = Path().apply {
            moveTo(4f * u, 11.2f * u)
            lineTo(12f * u, 4.4f * u)
            lineTo(20f * u, 11.2f * u)
            moveTo(6.2f * u, 9.6f * u)
            lineTo(6.2f * u, 19.2f * u)
            lineTo(9.8f * u, 19.2f * u)
            lineTo(9.8f * u, 14.2f * u)
            lineTo(14.2f * u, 14.2f * u)
            lineTo(14.2f * u, 19.2f * u)
            lineTo(17.8f * u, 19.2f * u)
            lineTo(17.8f * u, 9.6f * u)
        }
        drawPath(
            path = houseStroke,
            color = color,
            style = Stroke(width = 1.85f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        return
    }

    // Stage 1 (0.00..0.22): Solid house gently settles, thin vertical center door seam appears
    // Stage 2 (0.22..0.90): Center seam pulls smoothly open left & right into a small doorway rectangle
    // Stage 3 (0.90..1.00): Crisp elastic lock-in
    val settleLift = if (t < 0.35f) {
        -sin((t / 0.35f) * PI).toFloat() * 0.9f * u
    } else {
        0f
    }
    val seamAppear = (t / 0.22f).coerceIn(0f, 1f)
    val openRaw = ((t - 0.20f) / 0.80f).coerceIn(0f, 1f)
    val openEase = EaseOutBack.transform(openRaw).coerceIn(0f, 1.08f)

    // Door half-width expands from a crisp 0.38u seam to 2.35u (4.7u total width small rectangle)
    val maxHalfW = 2.35f * u
    val doorHalfW = (0.38f * u * seamAppear + (maxHalfW - 0.38f * u) * openEase)
        .coerceIn(0f, 2.55f * u)
    val doorTopY = (14.4f - 0.8f * openEase.coerceAtMost(1f)) * u // rises subtly to 13.6u as it opens
    val doorBotY = 20.4f * u

    translate(top = settleLift) {
        // Draw solid rounded house silhouette first
        val solidHouse = Path().apply {
            moveTo(12f * u, 3.8f * u)
            lineTo(20.6f * u, 11.2f * u)
            lineTo(18.5f * u, 11.2f * u)
            lineTo(18.5f * u, 19.1f * u)
            quadraticTo(18.5f * u, 20.0f * u, 17.5f * u, 20.0f * u)
            lineTo(6.5f * u, 20.0f * u)
            quadraticTo(5.5f * u, 20.0f * u, 5.5f * u, 19.1f * u)
            lineTo(5.5f * u, 11.2f * u)
            lineTo(3.4f * u, 11.2f * u)
            close()
        }
        drawPath(path = solidHouse, color = color)

        // Carve the opening doorway symmetrically from the center vertical seam using BlendMode.Clear
        if (doorHalfW > 0.1f) {
            val corner = (0.75f * u).coerceAtMost(doorHalfW * 0.85f)
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(12f * u - doorHalfW, doorTopY),
                size = Size(doorHalfW * 2f, doorBotY - doorTopY),
                cornerRadius = CornerRadius(corner, corner),
                blendMode = BlendMode.Clear
            )
        }
    }
}

private fun DrawScope.drawAnimatedPresetsIcon(
    u: Float,
    selected: Boolean,
    t: Float,
    color: Color
) {
    fun buildDiamondAt(centerY: Float, halfW: Float = 9.0f, halfH: Float = 7.0f): Path = Path().apply {
        moveTo(12f * u, (centerY - halfH) * u)
        lineTo((12f + halfW) * u, centerY * u)
        lineTo(12f * u, (centerY + halfH) * u)
        lineTo((12f - halfW) * u, centerY * u)
        close()
    }

    if (!selected) {
        val topOutline = buildDiamondAt(centerY = 9.0f, halfW = 7.8f, halfH = 6.0f)
        drawPath(
            path = topOutline,
            color = color,
            style = Stroke(width = 1.75f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        val bottomStroke = Path().apply {
            moveTo(4.2f * u, 13.8f * u)
            lineTo(12f * u, 19.9f * u)
            lineTo(19.8f * u, 13.8f * u)
        }
        drawPath(
            path = bottomStroke,
            color = color,
            style = Stroke(width = 1.75f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        return
    }

    // Both full 9x7 diamond sheets are rendered (matching exact ic_layers.xml proportions).
    // Stage 1 (0.00..0.24): Lower full sheet settles while upper full sheet hovers above it.
    // Stage 2 (0.22..1.00): Upper full sheet drops down to cover the lower sheet, carving an
    // exact same-size 9x7 cutout shifted +2.54u below it so the final resting silhouette matches
    // ic_layers.xml's blunt-ended bottom chevron and top diamond to the pixel.
    val pLowerIn = (t / 0.24f).coerceIn(0f, 1f)
    val easeLower = EaseOutCubic.transform(pLowerIn)

    val pDrop = ((t - 0.22f) / 0.78f).coerceIn(0f, 1f)
    val easeDrop = EaseOutBack.transform(pDrop)

    val recoil = if (pDrop > 0.56f) {
        val rPhase = ((pDrop - 0.56f) / 0.44f).coerceIn(0f, 1f)
        sin(rPhase * PI).toFloat() * 0.85f
    } else {
        0f
    }

    val lowerCenterY = 15.27f - 1.20f * easeLower + recoil
    val upperCenterY = 3.8f + (9.0f - 3.8f) * easeDrop
    val gapShift = 1.10f + (2.54f - 1.10f) * EaseOutCubic.transform(pDrop)

    // 1. Lower full diamond sheet
    drawPath(path = buildDiamondAt(centerY = lowerCenterY), color = color)

    // 2. Clean occlusion cutout (exact same 9x7 width so lower chevron ends stay cleanly beveled like ic_layers.xml, never sharp needles)
    val clearBand = Path().apply {
        val topCY = upperCenterY
        val botCY = upperCenterY + gapShift
        moveTo(12f * u, (topCY - 7.0f) * u)
        lineTo(21f * u, topCY * u)
        lineTo(21f * u, botCY * u)
        lineTo(12f * u, (botCY + 7.0f) * u)
        lineTo(3f * u, botCY * u)
        lineTo(3f * u, topCY * u)
        close()
    }
    drawPath(
        path = clearBand,
        color = Color.Transparent,
        blendMode = BlendMode.Clear
    )

    // 3. Upper full diamond sheet (resting at centerY = 9.0, exact ic_layers.xml top diamond)
    drawPath(path = buildDiamondAt(centerY = upperCenterY), color = color)
}

private fun DrawScope.drawAnimatedTuningIcon(
    u: Float,
    selected: Boolean,
    t: Float,
    color: Color
) {
    val rowsY = floatArrayOf(6f, 12f, 18f)
    val strokeW = 1.75f * u

    if (!selected) {
        val knobXs = floatArrayOf(15.5f, 8.5f, 13.5f)
        val r = 2.25f * u
        for (i in 0..2) {
            val y = rowsY[i] * u
            val kx = knobXs[i] * u
            if (kx - r > 3.5f * u) {
                drawLine(
                    color = color,
                    start = Offset(3.5f * u, y),
                    end = Offset(kx - r, y),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
            if (kx + r < 20.5f * u) {
                drawLine(
                    color = color,
                    start = Offset(kx + r, y),
                    end = Offset(20.5f * u, y),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
            drawCircle(
                color = color,
                radius = r,
                center = Offset(kx, y),
                style = Stroke(width = strokeW)
            )
        }
        return
    }

    // Two-beat fine-tuning equalizer glide: each knob sweeps across then dials back into its sweet spot
    val startXs = floatArrayOf(15.5f, 8.5f, 13.5f)
    val midXs = floatArrayOf(8.2f, 16.0f, 7.6f)
    val endXs = floatArrayOf(16.0f, 8.0f, 12.6f)
    val delays = floatArrayOf(0.00f, 0.08f, 0.16f)

    for (i in 0..2) {
        val localT = ((t - delays[i]) / (1f - delays[i])).coerceIn(0f, 1f)
        val posX = if (localT < 0.42f) {
            val e = EaseInOutCubic.transform((localT / 0.42f).coerceIn(0f, 1f))
            startXs[i] + (midXs[i] - startXs[i]) * e
        } else {
            val e = EaseOutBack.transform(((localT - 0.42f) / 0.58f).coerceIn(0f, 1f))
            midXs[i] + (endXs[i] - midXs[i]) * e
        }
        val kx = posX.coerceIn(5.0f, 19.0f) * u
        val y = rowsY[i] * u
        val gap = 2.6f * u

        if (kx - gap > 3.2f * u) {
            drawLine(
                color = color,
                start = Offset(3.2f * u, y),
                end = Offset(kx - gap, y),
                strokeWidth = 2.0f * u,
                cap = StrokeCap.Round
            )
        }
        if (kx + gap < 20.8f * u) {
            drawLine(
                color = color,
                start = Offset(kx + gap, y),
                end = Offset(20.8f * u, y),
                strokeWidth = 2.0f * u,
                cap = StrokeCap.Round
            )
        }

        val thumbW = 2.35f * u
        val thumbH = (4.6f + 1.2f * EaseOutCubic.transform(localT)) * u
        drawRoundRect(
            color = color,
            topLeft = Offset(kx - thumbW * 0.5f, y - thumbH * 0.5f),
            size = Size(thumbW, thumbH),
            cornerRadius = CornerRadius(thumbW * 0.5f, thumbW * 0.5f)
        )
    }
}

private fun DrawScope.drawAnimatedLabIcon(
    u: Float,
    selected: Boolean,
    t: Float,
    color: Color
) {
    fun buildFlaskBody(): Path = Path().apply {
        moveTo(9.04f * u, 4.4f * u)
        lineTo(14.96f * u, 4.4f * u)
        cubicTo(15.38f * u, 4.4f * u, 15.61f * u, 4.88f * u, 15.35f * u, 5.21f * u)
        lineTo(14f * u, 6.8f * u)
        lineTo(14f * u, 10.9f * u)
        lineTo(19.8f * u, 18.6f * u)
        cubicTo(20.29f * u, 19.26f * u, 19.82f * u, 20.2f * u, 19f * u, 20.2f * u)
        lineTo(5f * u, 20.2f * u)
        cubicTo(4.18f * u, 20.2f * u, 3.71f * u, 19.26f * u, 4.2f * u, 18.6f * u)
        lineTo(10f * u, 10.9f * u)
        lineTo(10f * u, 6.8f * u)
        lineTo(8.65f * u, 5.21f * u)
        cubicTo(8.39f * u, 4.88f * u, 8.62f * u, 4.4f * u, 9.04f * u, 4.4f * u)
        close()
    }

    val flaskPath = buildFlaskBody()

    if (!selected) {
        drawPath(
            path = flaskPath,
            color = color,
            style = Stroke(width = 1.75f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        return
    }

    // Stage 1 (0.00..0.52): Flask gently swirls as liquid rises up to fill the conical body
    // Stage 2 (0.18..0.98): Three crisp reaction bubbles rise inside the liquid (punched clear),
    //                       emerge out of the flask mouth, expand, and pop above the rim!
    val swirlFade = (1f - (t / 0.70f).coerceIn(0f, 1f))
    val swirlDeg = -sin(t * 2.5 * PI).toFloat() * swirlFade * 13f
    val fillRaw = (t / 0.56f).coerceIn(0f, 1f)
    val fillFrac = EaseOutCubic.transform(fillRaw)

    rotate(degrees = swirlDeg, pivot = Offset(12f * u, 14.5f * u)) {
        if (fillFrac >= 0.99f) {
            drawPath(path = flaskPath, color = color)
        } else {
            drawPath(
                path = flaskPath,
                color = color,
                style = Stroke(width = 1.75f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            clipPath(flaskPath) {
                val liquidTop = 20.5f * u - fillFrac * 16.6f * u
                val slosh = sin(t * 4.0 * PI).toFloat() * (1f - fillFrac) * 1.4f * u
                val liquidPath = Path().apply {
                    moveTo(0f, liquidTop - slosh)
                    quadraticTo(12f * u, liquidTop + slosh, 24f * u, liquidTop - slosh)
                    lineTo(24f * u, 24f * u)
                    lineTo(0f, 24f * u)
                    close()
                }
                drawPath(path = liquidPath, color = color)
            }
        }

        // Helper for a bubble rising inside the flask (BlendMode.Clear) then emerging & popping above rim!
        fun drawReactionBubble(
            startT: Float,
            endT: Float,
            startX: Float,
            endX: Float,
            startY: Float,
            endY: Float,
            maxRadius: Float
        ) {
            if (t <= startT || t >= endT) return
            val bp = ((t - startT) / (endT - startT)).coerceIn(0f, 1f)
            val eased = EaseOutCubic.transform(bp)
            val bx = (startX + (endX - startX) * eased + sin(bp * 2.0 * PI).toFloat() * 0.45f) * u
            val by = (startY + (endY - startY) * eased) * u
            val r = maxRadius * (0.45f + 0.55f * sin(bp.coerceAtMost(0.85f) / 0.85f * PI * 0.5).toFloat()) * u

            if (by > 4.6f * u) {
                // Inside flask: punch crisp translucent bubble out of the liquid
                drawCircle(
                    color = Color.Transparent,
                    radius = r,
                    center = Offset(bx, by),
                    blendMode = BlendMode.Clear
                )
            } else {
                // Emerged above flask rim: visible rising bubble that expands and pops!
                val fadeOut = if (bp > 0.78f) ((1f - bp) / 0.22f).coerceIn(0f, 1f) else 1f
                if (bp < 0.84f) {
                    drawCircle(
                        color = color.copy(alpha = color.alpha * fadeOut),
                        radius = r,
                        center = Offset(bx, by),
                        style = Stroke(width = 1.2f * u)
                    )
                } else {
                    // Subtle pop ring burst
                    val popP = ((bp - 0.84f) / 0.16f).coerceIn(0f, 1f)
                    drawCircle(
                        color = color.copy(alpha = color.alpha * (1f - popP)),
                        radius = r * (1f + popP * 0.65f),
                        center = Offset(bx, by),
                        style = Stroke(width = 0.9f * u)
                    )
                }
            }
        }

        // Three distinct rising bubbles so the reaction is unmistakably lively
        drawReactionBubble(
            startT = 0.14f, endT = 0.72f,
            startX = 10.6f, endX = 10.9f,
            startY = 17.5f, endY = 1.2f,
            maxRadius = 1.45f
        )
        drawReactionBubble(
            startT = 0.30f, endT = 0.86f,
            startX = 13.4f, endX = 13.6f,
            startY = 18.0f, endY = 1.8f,
            maxRadius = 1.25f
        )
        drawReactionBubble(
            startT = 0.48f, endT = 0.98f,
            startX = 11.8f, endX = 11.4f,
            startY = 15.5f, endY = 0.9f,
            maxRadius = 1.55f
        )
    }
}

/**
 * Gmail-inspired animated trash can icon for swipe-to-delete capsules:
 * - Slight lid anticipation crack as drag progresses toward the 2/5 threshold.
 * - Lid flips open (-30°) with bouncy spring + subtle body wobble when crossing the delete threshold.
 */
@Composable
fun AnimatedSwipeTrashIcon(
    swipeProgress: Float,
    pastThreshold: Boolean,
    swipingRight: Boolean,
    tint: Color,
    cutoutColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    val lidOpen by animateFloatAsState(
        targetValue = if (pastThreshold) 1f else (swipeProgress.coerceIn(0f, 1f) * 0.18f),
        animationSpec = spring(dampingRatio = 0.46f, stiffness = 460f),
        label = "trashLidOpen"
    )

    val wobble = remember { Animatable(0f) }
    var prevPast by remember { mutableStateOf(pastThreshold) }
    LaunchedEffect(pastThreshold) {
        if (pastThreshold && !prevPast) {
            wobble.snapTo(0f)
            val dir = if (swipingRight) 1f else -1f
            wobble.animateTo(dir * 9f, tween(80, easing = FastOutSlowInEasing))
            wobble.animateTo(-dir * 5f, tween(90, easing = FastOutSlowInEasing))
            wobble.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
        }
        prevPast = pastThreshold
    }

    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val hingeSign = if (swipingRight) -1f else 1f

        rotate(degrees = wobble.value, pivot = Offset(12f * u, 14f * u)) {
            // Tapered rounded bin body
            val bodyPath = Path().apply {
                moveTo(6.6f * u, 9.0f * u)
                lineTo(17.4f * u, 9.0f * u)
                lineTo(16.6f * u, 18.8f * u)
                cubicTo(16.5f * u, 20.0f * u, 15.6f * u, 20.8f * u, 14.4f * u, 20.8f * u)
                lineTo(9.6f * u, 20.8f * u)
                cubicTo(8.4f * u, 20.8f * u, 7.5f * u, 20.0f * u, 7.4f * u, 18.8f * u)
                close()
            }
            drawPath(path = bodyPath, color = tint)

            // Two crisp inner vertical ribs
            drawLine(
                color = cutoutColor,
                start = Offset(10.1f * u, 11.6f * u),
                end = Offset(10.3f * u, 18.0f * u),
                strokeWidth = 1.45f * u,
                cap = StrokeCap.Round
            )
            drawLine(
                color = cutoutColor,
                start = Offset(13.9f * u, 11.6f * u),
                end = Offset(13.7f * u, 18.0f * u),
                strokeWidth = 1.45f * u,
                cap = StrokeCap.Round
            )

            // Animated hinged lid + handle
            val pivotX = if (swipingRight) 6.2f * u else 17.8f * u
            val pivotY = 7.0f * u
            val lidAngle = hingeSign * lidOpen * 30f
            val lidLift = -lidOpen * 1.9f * u

            translate(top = lidLift) {
                rotate(degrees = lidAngle, pivot = Offset(pivotX, pivotY)) {
                    // Lid bar
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(5.0f * u, 5.8f * u),
                        size = Size(14.0f * u, 2.0f * u),
                        cornerRadius = CornerRadius(1.0f * u, 1.0f * u)
                    )
                    // Lid handle
                    val handlePath = Path().apply {
                        moveTo(9.5f * u, 6.0f * u)
                        lineTo(9.9f * u, 4.5f * u)
                        quadraticTo(10.2f * u, 3.6f * u, 11.1f * u, 3.6f * u)
                        lineTo(12.9f * u, 3.6f * u)
                        quadraticTo(13.8f * u, 3.6f * u, 14.1f * u, 4.5f * u)
                        lineTo(14.5f * u, 6.0f * u)
                    }
                    drawPath(
                        path = handlePath,
                        color = tint,
                        style = Stroke(width = 1.65f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}

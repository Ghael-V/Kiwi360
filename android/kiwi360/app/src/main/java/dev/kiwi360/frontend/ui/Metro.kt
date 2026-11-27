// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.graphics.BitmapFactory
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import jp.xenia.emulator.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Kiwi360 palette: a light, airy dashboard with the logo's green as accent. */
object Kiwi {
    val Green = Color(0xFF86C232)
    val GreenDeep = Color(0xFF4E8A1C)
    val GreenPale = Color(0xFFCFE8A8)
    val Ink = Color(0xFF23271F)
    val InkSoft = Color(0xFF8E9488)
    val BackgroundTop = Color(0xFFF5F6F2)
    val BackgroundBottom = Color(0xFFD8DDD1)
    val Slate = Color(0xFF3B4136)
    val ButtonA = Color(0xFF5DB12F)
    val ButtonB = Color(0xFFD8463E)
    val ButtonX = Color(0xFF2F7ED0)
    val ButtonY = Color(0xFFE6B52C)
}

/** DM Sans (SIL OFL 1.1), a variable font: one file, every weight the UI uses. */
@OptIn(ExperimentalTextApi::class)
val KiwiFont = FontFamily(
    listOf(300, 400, 500, 600).map { weight ->
        Font(
            R.font.dm_sans,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/** Room around tile lanes, so a focused tile can grow without being clipped. */
val LanePadding = 16.dp

/** The gradient with slow drifting light blobs behind every screen. */
@Composable
fun MetroBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Kiwi.BackgroundTop, Kiwi.BackgroundBottom))),
    ) {
        val transition = rememberInfiniteTransition(label = "background")
        val t by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
            label = "drift",
        )
        Canvas(Modifier.fillMaxSize()) {
            fun blob(x: Float, y: Float, radius: Float, phase: Float, color: Color) {
                val angle = (t + phase) * 2 * PI
                val center = Offset(
                    size.width * x + cos(angle).toFloat() * size.width * 0.06f,
                    size.height * y + sin(angle).toFloat() * size.height * 0.08f,
                )
                val r = size.height * radius
                drawCircle(
                    Brush.radialGradient(listOf(color, Color.Transparent), center, r),
                    radius = r,
                    center = center,
                )
            }
            blob(0.85f, 0.15f, 0.75f, 0f, Kiwi.GreenPale.copy(alpha = 0.55f))
            blob(0.15f, 0.95f, 0.6f, 0.35f, Kiwi.Green.copy(alpha = 0.18f))
            blob(0.55f, 0.6f, 0.45f, 0.7f, Color.White.copy(alpha = 0.6f))
        }
        content()
    }
}

/**
 * Lowercase section titles; LB/RB or a tap switches between them. Each title
 * stays on one line and the strip runs off the screen when they don't fit (they
 * are longer in some languages), scrolling to keep the selected one in view.
 */
@Composable
fun PivotHeader(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // Where each title starts and ends inside the strip, in px.
    val bounds = remember { mutableStateMapOf<Int, IntRange>() }
    LaunchedEffect(selected, bounds[selected], scroll.viewportSize) {
        val range = bounds[selected] ?: return@LaunchedEffect
        val target = when {
            range.last > scroll.value + scroll.viewportSize -> range.last - scroll.viewportSize
            range.first < scroll.value -> range.first
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target)
    }
    Row(
        modifier.horizontalScroll(scroll, enabled = false),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        titles.forEachIndexed { index, title ->
            val color by animateColorAsState(
                if (index == selected) Kiwi.Ink else Kiwi.InkSoft.copy(alpha = 0.7f),
                label = "pivot",
            )
            Text(
                title,
                color = color,
                fontSize = 46.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .onPlaced { val x = it.positionInParent().x.toInt(); bounds[index] = x..x + it.size.width }
                    .focusProperties { canFocus = false }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
            )
        }
    }
}

/**
 * A dashboard tile: flat colour, grows and gets a white frame when focused.
 * Y on a focused tile runs [onSecondary] (details), A or a tap runs [onClick],
 * X or a long press runs [onOptions].
 */
@Composable
fun MetroTile(
    width: Dp,
    height: Dp,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSecondary: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onOptions: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tile")
    Box(
        modifier
            .size(width, height)
            .zIndex(if (focused) 1f else 0f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    // A arrives as the d-pad centre (see LibraryActivity).
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.type == KeyEventType.KeyUp) onClick()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BUTTON_Y -> {
                        if (onSecondary == null) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) onSecondary()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BUTTON_X -> {
                        if (onOptions == null) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) onOptions()
                        true
                    }
                    else -> false
                }
            }
            // Focusable in touch mode too: a console UI always has a selection.
            .focusable()
            .pointerInput(onClick, onOptions) {
                detectTapGestures(onLongPress = onOptions?.let { options -> { options() } }) { onClick() }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 18.dp else 3.dp, RectangleShape)
            .background(color)
            .then(if (focused) Modifier.border(3.dp, Color.White) else Modifier),
    ) {
        content(focused)
    }
}

/** Controller hints along the bottom edge: coloured face buttons and their action. */
@Composable
fun ButtonHints(hints: List<Pair<String, String>>, modifier: Modifier = Modifier, textColor: Color = Kiwi.Ink) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((button, label) in hints) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when (button) {
                    "A" -> Kiwi.ButtonA
                    "B" -> Kiwi.ButtonB
                    "X" -> Kiwi.ButtonX
                    "Y" -> Kiwi.ButtonY
                    else -> Kiwi.Slate
                }
                Box(
                    Modifier
                        .size(
                            when {
                                button.length > 2 -> 54.dp
                                button.length > 1 -> 34.dp
                                else -> 26.dp
                            },
                            26.dp,
                        )
                        .background(color, if (button.length > 1) RectangleShape else CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        button, color = Color.White, fontSize = if (button.length > 2) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                }
                if (label.isNotEmpty()) {
                    Text(label, color = textColor, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Decodes an image file off the main thread, scaled down to about [maxSize] pixels. */
@Composable
fun rememberImage(path: String?, maxSize: Int = 512): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return image
}

/** A tile colour taken from an icon: its average opaque colour, deepened for white text. */
fun tileColorFor(image: ImageBitmap?, fallback: Color): Color {
    if (image == null) return fallback
    val pixels = IntArray(image.width * image.height)
    image.readPixels(pixels)
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
        n++
    }
    if (n == 0L) return fallback
    val avg = Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    return Color(avg.red * 0.62f, avg.green * 0.62f, avg.blue * 0.62f)
}

/** A stable, muted tile colour per game when it has no art of its own. */
fun tileColorForKey(key: String): Color {
    val palette = listOf(
        Color(0xFF4E8A1C), Color(0xFF2F6FB5), Color(0xFF1C8C78), Color(0xFFB4532A),
        Color(0xFF6E4AA6), Color(0xFF8A6D1C), Color(0xFFA63A5B), Color(0xFF3B4136),
    )
    return palette[Math.floorMod(key.hashCode(), palette.size)]
}

/**
 * A title icon on a tile: the icon itself, sharp and at a modest size, over
 * a blurred, darkened copy that fills the whole tile.
 */
@Composable
fun BoxScope.IconArt(icon: ImageBitmap, iconSize: Dp, modifier: Modifier = Modifier) {
    Image(
        icon, null,
        Modifier.matchParentSize().blur(30.dp),
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low,
    )
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
    Image(
        icon, null,
        modifier.align(Alignment.Center).size(iconSize).shadow(10.dp, RectangleShape),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
    )
}

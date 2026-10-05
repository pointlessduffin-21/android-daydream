package com.daydream.standby.ui.standby.photos

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.daydream.standby.data.photos.Slide
import com.daydream.standby.data.photos.SlideLayout
import com.daydream.standby.data.photos.SlidePhoto
import com.daydream.standby.ui.common.toSlideRequest
import kotlinx.coroutines.delay

private val GAP = 6.dp
private val TILE_RADIUS = 14.dp
private const val TILE_STAGGER_MILLIS = 150L
private const val TILE_FADE_MILLIS = 600
private const val TILE_INTENSITY = 0.6f
private const val PORTRAIT_INTENSITY = 0.4f
private const val BACKDROP_SCALE = 1.3f
private const val BACKDROP_DIM = 0.45f

/**
 * Renders one [Slide] in its layout. [motionMillis] is how long the Ken Burns motion lasts
 * (the slide interval plus the crossfade, so it never stops on screen).
 */
@Composable
fun SlideView(slide: Slide, motionMillis: Int, kenBurns: Boolean, modifier: Modifier = Modifier) {
    val motion = kenBurns && !rememberAnimationsDisabled()
    val photos = slide.photos
    when (slide.layout) {
        SlideLayout.SINGLE -> KenBurnsImage(photos[0], motionMillis, if (motion) 1f else 0f, ContentScale.Crop, modifier.fillMaxSize())
        SlideLayout.SINGLE_PORTRAIT -> FittedWithBackdrop(photos[0], motionMillis, motion, modifier)
        SlideLayout.DUO, SlideLayout.TRIO -> Strip(slide.vertical, modifier.fillMaxSize().padding(GAP)) {
            photos.forEachIndexed { i, p -> Tile(p, i, motionMillis, motion, Modifier.share(1f)) }
        }
        SlideLayout.MOSAIC -> Strip(slide.vertical, modifier.fillMaxSize().padding(GAP)) {
            Tile(photos[0], 0, motionMillis, motion, Modifier.share(1f))
            // The two wide photos stack across the other axis.
            Strip(!slide.vertical, Modifier.share(1f)) {
                Tile(photos[1], 1, motionMillis, motion, Modifier.share(1f))
                Tile(photos[2], 2, motionMillis, motion, Modifier.share(1f))
            }
        }
    }
}

/** A Row, or a Column when [vertical]; children size themselves with `Modifier.share`. */
@Composable
private fun Strip(vertical: Boolean, modifier: Modifier, content: @Composable StripScope.() -> Unit) {
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(GAP)) { ColumnStrip(this).content() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(GAP)) { RowStrip(this).content() }
    }
}

/** Lets layout code size children along the strip's axis without knowing if it's a Row or Column. */
private interface StripScope {
    fun Modifier.share(weight: Float): Modifier
}

private class RowStrip(private val scope: RowScope) : StripScope {
    override fun Modifier.share(weight: Float): Modifier = with(scope) { this@share.weight(weight) }
}

private class ColumnStrip(private val scope: ColumnScope) : StripScope {
    override fun Modifier.share(weight: Float): Modifier = with(scope) { this@share.weight(weight) }
}

@Composable
private fun Tile(photo: SlidePhoto, index: Int, motionMillis: Int, motion: Boolean, modifier: Modifier) {
    // Tiles after the first fade in one by one for a gentle "assembling" effect.
    val alpha = remember(photo.photo.id) { Animatable(if (index == 0) 1f else 0f) }
    LaunchedEffect(photo.photo.id) {
        if (index > 0) {
            delay(TILE_STAGGER_MILLIS * index)
            alpha.animateTo(1f, tween(TILE_FADE_MILLIS))
        }
    }
    KenBurnsImage(
        photo = photo,
        motionMillis = motionMillis,
        intensity = if (motion) TILE_INTENSITY else 0f,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .clip(RoundedCornerShape(TILE_RADIUS)),
    )
}

/** A whole portrait over a blurred, dimmed, enlarged copy of itself. */
@Composable
private fun FittedWithBackdrop(photo: SlidePhoto, motionMillis: Int, motion: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(photo.photo.id) { photo.photo.toSlideRequest(context) }
    Box(modifier.fillMaxSize().clipToBounds()) {
        // Modifier.blur is a no-op before API 31; the dim layer keeps the backdrop subdued there too.
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = BACKDROP_SCALE
                    scaleY = BACKDROP_SCALE
                }
                .blur(48.dp),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_DIM)))
        KenBurnsImage(photo, motionMillis, if (motion) PORTRAIT_INTENSITY else 0f, ContentScale.Fit, Modifier.fillMaxSize())
    }
}

@Composable
private fun KenBurnsImage(photo: SlidePhoto, motionMillis: Int, intensity: Float, contentScale: ContentScale, modifier: Modifier) {
    val context = LocalContext.current
    val id = photo.photo.id
    val request = remember(id) { photo.photo.toSlideRequest(context) }
    val spec = remember(id, intensity) { KenBurnsSpec.random(seed = id.hashCode().toLong(), intensity = intensity) }
    val progress = remember(id) { Animatable(0f) }
    LaunchedEffect(id, spec, motionMillis) {
        if (spec != KenBurnsSpec.STILL) progress.animateTo(1f, tween(motionMillis, easing = LinearEasing))
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
            .clipToBounds()
            .graphicsLayer {
                val frame = spec.at(progress.value)
                scaleX = frame.scale
                scaleY = frame.scale
                translationX = frame.offsetX * size.width
                translationY = frame.offsetY * size.height
            },
    )
}

/** True when the user turned animations off (Developer options / Accessibility "Remove animations"). */
@Composable
private fun rememberAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

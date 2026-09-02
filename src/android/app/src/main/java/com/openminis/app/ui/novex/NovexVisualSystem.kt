package com.openminis.app.ui.novex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.openminis.app.R

internal object NovexColors {
    val Background = Color(0xFFFBFBFC)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF17181C)
    val SecondaryText = Color(0xFF686B73)
    val Divider = Color(0xFFE7E8EC)
    val Primary = Color(0xFF315F9F)
    val PrimarySoft = Color(0xFFEEF3FA)
}

internal enum class NovexArtworkKind {
    WORLD,
    CHARACTER,
}

/** Shared media surface for root cards, detail heroes and previews. */
@Composable
internal fun NovexArtwork(
    kind: NovexArtworkKind,
    seed: String,
    imageModel: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        when (kind) {
            NovexArtworkKind.WORLD -> NovexDefaultWorldArtwork(seed, modifier)
            NovexArtworkKind.CHARACTER -> NovexDefaultCharacterArtwork(seed, modifier)
        }
    }
}

internal fun novexArtworkVariant(seed: String, variantCount: Int): Int {
    require(variantCount > 0) { "视觉方案数量必须大于零" }
    return Math.floorMod(seed.hashCode(), variantCount)
}

@Composable
private fun NovexDefaultWorldArtwork(seed: String, modifier: Modifier) {
    val artworks = intArrayOf(
        R.drawable.novex_world_default_mountain,
        R.drawable.novex_world_default_neon,
        R.drawable.novex_world_default_village,
        R.drawable.novex_world_default_space,
    )
    Box(modifier) {
        Image(
            painter = painterResource(artworks[novexArtworkVariant(seed, artworks.size)]),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.White.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun NovexDefaultCharacterArtwork(seed: String, modifier: Modifier) {
    val palettes = listOf(
        Color(0xFFD9E1EA) to Color(0xFF9BAEC2),
        Color(0xFFE7DDE5) to Color(0xFFB4A1B0),
        Color(0xFFDDE7DF) to Color(0xFF9CB3A3),
        Color(0xFFE7E1D8) to Color(0xFFB9A994),
    )
    val colors = palettes[novexArtworkVariant(seed, palettes.size)]
    Canvas(modifier.background(Brush.linearGradient(listOf(colors.first, Color.White)))) {
        drawCircle(
            color = Color.White.copy(alpha = 0.34f),
            radius = size.width * 0.34f,
            center = Offset(size.width * 0.18f, size.height * 0.2f),
        )
        drawCircle(
            color = colors.second.copy(alpha = 0.38f),
            radius = size.width * 0.14f,
            center = Offset(size.width * 0.74f, size.height * 0.34f),
        )
        drawOval(
            color = colors.second.copy(alpha = 0.3f),
            topLeft = Offset(size.width * 0.5f, size.height * 0.56f),
            size = Size(size.width * 0.48f, size.height * 0.5f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(colors.second.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.76f, size.height * 0.34f),
                radius = size.width * 0.48f,
            ),
            radius = size.width * 0.48f,
            center = Offset(size.width * 0.76f, size.height * 0.34f),
        )
    }
}

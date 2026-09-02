package com.openminis.app.ui.novex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

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
    val palettes = listOf(
        listOf(Color(0xFF5F7796), Color(0xFFB2C3D5), Color(0xFFE4D8C9)),
        listOf(Color(0xFF6D617F), Color(0xFFB59EB5), Color(0xFFE0C5B9)),
        listOf(Color(0xFF526F6A), Color(0xFF9DB7A7), Color(0xFFE0D7B7)),
        listOf(Color(0xFF596579), Color(0xFF8DA2B8), Color(0xFFCFB7A7)),
    )
    val colors = palettes[novexArtworkVariant(seed, palettes.size)]
    Canvas(modifier) {
        drawRect(Brush.linearGradient(colors))
        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = size.minDimension * 0.34f,
            center = Offset(size.width * 0.78f, size.height * 0.2f),
        )
        val back = Path().apply {
            moveTo(0f, size.height * 0.72f)
            lineTo(size.width * 0.24f, size.height * 0.38f)
            lineTo(size.width * 0.48f, size.height * 0.68f)
            lineTo(size.width * 0.7f, size.height * 0.3f)
            lineTo(size.width, size.height * 0.66f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(back, Color(0xFF26394E).copy(alpha = 0.34f))
        val front = Path().apply {
            moveTo(0f, size.height * 0.82f)
            lineTo(size.width * 0.32f, size.height * 0.62f)
            lineTo(size.width * 0.58f, size.height * 0.82f)
            lineTo(size.width * 0.84f, size.height * 0.55f)
            lineTo(size.width, size.height * 0.74f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(front, Color(0xFF182B3D).copy(alpha = 0.48f))
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
    Canvas(modifier.background(colors.first)) {
        drawCircle(
            color = colors.second.copy(alpha = 0.78f),
            radius = size.width * 0.22f,
            center = Offset(size.width / 2f, size.height * 0.34f),
        )
        drawOval(
            color = colors.second.copy(alpha = 0.72f),
            topLeft = Offset(size.width * 0.18f, size.height * 0.58f),
            size = Size(size.width * 0.64f, size.height * 0.55f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(size.width * 0.25f, size.height * 0.2f),
                radius = size.width * 0.55f,
            ),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.25f, size.height * 0.2f),
        )
    }
}

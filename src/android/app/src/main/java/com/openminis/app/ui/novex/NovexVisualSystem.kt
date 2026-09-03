package com.openminis.app.ui.novex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.R

internal data class NovexSurfacePalette(
    val canvas: Color,
    val grouped: Color,
    val section: Color,
    val muted: Color,
)

/**
 * A page chooses a semantic tone; it never chooses a raw theme surface.
 * Reading surfaces stay quiet while catalog, editing and settings surfaces
 * retain the grouped-background hierarchy used by the mature Minis layouts.
 */
internal enum class NovexPageTone {
    CONVERSATION,
    CATALOG,
    DISPLAY,
    EDITOR,
    SETTINGS;

    fun resolve(palette: NovexSurfacePalette): Color = when (this) {
        CONVERSATION, DISPLAY -> palette.canvas
        CATALOG, EDITOR, SETTINGS -> palette.grouped
    }
}

internal data class NovexLayoutMetrics(
    val pageHorizontal: Dp,
    val overlayHorizontal: Dp,
)

internal val NovexLayout = NovexLayoutMetrics(
    pageHorizontal = 16.dp,
    overlayHorizontal = 24.dp,
)

internal object NovexColors {
    private val palette: NovexSurfacePalette
        @Composable @ReadOnlyComposable get() = NovexSurfacePalette(
            canvas = MaterialTheme.colorScheme.surfaceContainerLow,
            grouped = MaterialTheme.colorScheme.background,
            section = MaterialTheme.colorScheme.surfaceContainerLow,
            muted = MaterialTheme.colorScheme.surfaceContainerHigh,
        )

    val Background: Color
        @Composable @ReadOnlyComposable get() = palette.grouped
    val Canvas: Color
        @Composable @ReadOnlyComposable get() = palette.canvas
    val Surface: Color
        @Composable @ReadOnlyComposable get() = palette.section
    val SurfaceMuted: Color
        @Composable @ReadOnlyComposable get() = palette.muted
    val Text: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground
    val SecondaryText: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val TertiaryText: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val Divider: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val Primary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val PrimarySoft: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
    val Danger: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
    val ImageScrim = Color(0xB8000000)
}

internal val NovexPageTone.color: Color
    @Composable @ReadOnlyComposable get() = resolve(
        NovexSurfacePalette(
            canvas = NovexColors.Canvas,
            grouped = NovexColors.Background,
            section = NovexColors.Surface,
            muted = NovexColors.SurfaceMuted,
        ),
    )

internal object NovexDimensions {
    val PageHorizontal = NovexLayout.pageHorizontal
    val OverlayHorizontal = NovexLayout.overlayHorizontal
    val TopBarHeight = 56.dp
    val MinimumTouch = 48.dp
    val RootBottomInset = 104.dp
    val SectionGap = 24.dp
    val RowVertical = 10.dp
    val SmallRadius = 8.dp
    val SectionRadius = 12.dp
    val PopupRadius = 16.dp
    val DialogRadius = 20.dp
    val MediaRadius = 9.dp
    val SheetRadius = 24.dp
    val SettingsRowMinHeight = 56.dp
    val Hairline = 0.75.dp
    val ActionIconTile = 30.dp
}

/** Shared outer rail for conversation, world and character page content. */
internal fun novexPagePadding(bottom: Dp = 0.dp): PaddingValues = PaddingValues(
    start = NovexDimensions.PageHorizontal,
    end = NovexDimensions.PageHorizontal,
    bottom = bottom,
)

internal data class NovexTypography(
    val brand: androidx.compose.ui.text.TextStyle,
    val pageTitle: androidx.compose.ui.text.TextStyle,
    val sectionTitle: androidx.compose.ui.text.TextStyle,
    val itemTitle: androidx.compose.ui.text.TextStyle,
    val body: androidx.compose.ui.text.TextStyle,
    val metadata: androidx.compose.ui.text.TextStyle,
)

internal fun resolveNovexTypography(
    applicationTypography: androidx.compose.material3.Typography,
): NovexTypography = NovexTypography(
    brand = applicationTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    pageTitle = applicationTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    sectionTitle = applicationTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    itemTitle = applicationTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    body = applicationTypography.bodyMedium.copy(fontWeight = FontWeight.Normal),
    metadata = applicationTypography.bodySmall.copy(fontWeight = FontWeight.Normal),
)

internal object NovexType {
    val Brand: androidx.compose.ui.text.TextStyle
        @Composable get() = resolveNovexTypography(MaterialTheme.typography).brand
    val PageTitle: androidx.compose.ui.text.TextStyle
        @Composable get() = resolveNovexTypography(MaterialTheme.typography).pageTitle
    val SectionTitle: androidx.compose.ui.text.TextStyle
        @Composable get() = resolveNovexTypography(MaterialTheme.typography).sectionTitle
    val ItemTitle: androidx.compose.ui.text.TextStyle
        @Composable get() = resolveNovexTypography(MaterialTheme.typography).itemTitle
    val Body: androidx.compose.ui.text.TextStyle
        @Composable get() = resolveNovexTypography(MaterialTheme.typography).body
    val Metadata: androidx.compose.ui.text.TextStyle
        @Composable get() = resolveNovexTypography(MaterialTheme.typography).metadata
}

internal enum class NovexArtworkKind {
    WORLD,
    CHARACTER,
}

internal data class NovexBuiltInArtwork(
    val id: String,
    val name: String,
    @androidx.annotation.DrawableRes val drawable: Int,
)

/**
 * Application-owned world artwork is addressed by public, stable IDs. It is not
 * copied into the user's media library and therefore never participates in media
 * reference counting.
 */
internal object NovexBuiltInWorldArtwork {
    val covers = listOf(
        NovexBuiltInArtwork(
            "world.cover.mountain-gate.v1",
            "山海门扉",
            R.drawable.novex_world_cover_mountain_gate,
        ),
        NovexBuiltInArtwork(
            "world.cover.future-city.v1",
            "未来都市",
            R.drawable.novex_world_cover_future_city,
        ),
        NovexBuiltInArtwork(
            "world.cover.cosmic-ruins.v1",
            "星海遗迹",
            R.drawable.novex_world_cover_cosmic_ruins,
        ),
        NovexBuiltInArtwork(
            "world.cover.warm-daily.v1",
            "温暖日常",
            R.drawable.novex_world_cover_warm_daily,
        ),
    )
    val backgrounds = listOf(
        NovexBuiltInArtwork(
            "world.background.mountain.v1",
            "山海薄雾",
            R.drawable.novex_world_background_mountain,
        ),
        NovexBuiltInArtwork(
            "world.background.cosmic.v1",
            "星海暮色",
            R.drawable.novex_world_background_cosmic,
        ),
        NovexBuiltInArtwork(
            "world.background.daily.v1",
            "温暖日常",
            R.drawable.novex_world_background_daily,
        ),
    )
    val coverIds: List<String> = covers.map { it.id }
    val backgroundIds: List<String> = backgrounds.map { it.id }

    fun stableCoverId(worldId: String): String =
        covers[novexArtworkVariant(worldId, covers.size)].id

    fun stableBackgroundId(worldId: String): String =
        backgrounds[novexArtworkVariant(worldId, backgrounds.size)].id

    fun cover(id: String?): NovexBuiltInArtwork? = covers.firstOrNull { it.id == id }

    fun background(id: String?): NovexBuiltInArtwork? = backgrounds.firstOrNull { it.id == id }
}

internal sealed interface NovexArtworkFallback {
    data class BuiltInWorldCover(val id: String) : NovexArtworkFallback
    data object NeutralEmpty : NovexArtworkFallback
}

internal fun novexArtworkFallback(kind: NovexArtworkKind, seed: String): NovexArtworkFallback =
    when (kind) {
        NovexArtworkKind.WORLD -> NovexArtworkFallback.BuiltInWorldCover(
            NovexBuiltInWorldArtwork.stableCoverId(seed),
        )
        NovexArtworkKind.CHARACTER -> NovexArtworkFallback.NeutralEmpty
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
        when (val fallback = novexArtworkFallback(kind, seed)) {
            is NovexArtworkFallback.BuiltInWorldCover -> NovexDefaultWorldArtwork(
                id = fallback.id,
                modifier = modifier,
            )
            NovexArtworkFallback.NeutralEmpty -> NovexNeutralArtwork(modifier)
        }
    }
}

internal fun novexArtworkVariant(seed: String, variantCount: Int): Int {
    require(variantCount > 0) { "视觉方案数量必须大于零" }
    return Math.floorMod(seed.hashCode(), variantCount)
}

@Composable
private fun NovexDefaultWorldArtwork(id: String, modifier: Modifier) {
    val artwork = requireNotNull(NovexBuiltInWorldArtwork.cover(id)) {
        "未知的内置世界封面：$id"
    }
    Box(modifier) {
        Image(
            painter = painterResource(artwork.drawable),
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
private fun NovexNeutralArtwork(modifier: Modifier) {
    val primarySoft = NovexColors.PrimarySoft
    val primary = NovexColors.Primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(primarySoft)
        }
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = primary.copy(alpha = 0.46f),
            modifier = Modifier.fillMaxSize(0.38f),
        )
    }
}

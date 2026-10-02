package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 「添加供应商」前置步：选择接入方式（provider onboarding）。
 *
 * 三张卡——智谱 / 深度求索 / 自定义——前两张带官方预设（base 预填、取钥
 * 链接、模型目录），第三张进既有手动流程。logo 均为磁贴形态（统一圆角
 * 裁切）：智谱黑底白 Z、DeepSeek 蓝鲸落在白底上、自定义用白底小鸟
 * （透明版黑线在深色模式会隐形，务必用白底版）。
 */
@Composable
fun ProviderOnboardingScreen(
    onBack: () -> Unit,
    onPickZhipu: () -> Unit,
    onPickDeepSeek: () -> Unit,
    onPickCustom: () -> Unit,
) {
    SettingsScaffold(title = "选择接入方式", onBack = onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NovexDimensions.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "选择要接入的模型供应商；官方预设会帮你填好接口地址和模型目录，只需要一把密钥。",
                style = NovexType.Body,
                color = NovexColors.SecondaryText,
            )
            ProviderPresetCard(
                logoRes = R.drawable.img_provider_zhipu,
                logoOnWhiteTile = false,
                title = "智谱（Z.ai）",
                description = "GLM 系列官方接口；内置模型目录，无需拉取",
                onClick = onPickZhipu,
            )
            ProviderPresetCard(
                logoRes = R.drawable.img_provider_deepseek,
                logoOnWhiteTile = true,
                title = "深度求索（DeepSeek）",
                description = "官方接口；模型目录自动拉取，失败时内置兜底",
                onClick = onPickDeepSeek,
            )
            ProviderPresetCard(
                logoRes = R.drawable.img_provider_custom,
                logoOnWhiteTile = false,
                title = "自定义",
                description = "OpenAI 兼容接口 / 中转站，地址与密钥全部手填",
                onClick = onPickCustom,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单张接入卡：logo 磁贴 + 名称 + 一句描述。 */
@Composable
private fun ProviderPresetCard(
    logoRes: Int,
    logoOnWhiteTile: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NovexColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoTile(logoRes, logoOnWhiteTile)
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = NovexType.ItemTitle, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = NovexType.Metadata,
                color = NovexColors.SecondaryText,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(NovexColors.SurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                NovexIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = NovexColors.TertiaryText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * logo 磁贴：48dp 圆角方 + 细描边（深色模式下黑底 logo 与卡面之间需要一道
 * 分界）。DeepSeek 鲸鱼是透明底，落在白底磁贴上保对比。
 */
@Composable
private fun LogoTile(logoRes: Int, logoOnWhiteTile: Boolean) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (logoOnWhiteTile) Color.White else Color.Transparent)
            .border(NovexDimensions.Hairline, NovexColors.Divider, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(logoRes),
            contentDescription = null,
            contentScale = if (logoOnWhiteTile) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Optional image field shared by world and character editors. */
@Composable
internal fun NovexOptionalImageRow(
    label: String,
    imageModel: Any?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Column(Modifier.weight(1f).padding(start = if (imageModel == null) 0.dp else 12.dp)) {
            Text(label, color = NovexColors.Text)
            Text(
                if (imageModel == null) "未设置（可留空）" else "已设置",
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
            )
        }
        NovexOutlineButton(
            label = if (imageModel == null) "选择" else "更换",
            onClick = onPick,
        )
        if (imageModel != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            NovexOutlineButton(label = "移除", onClick = onRemove, danger = true)
        }
    }
}

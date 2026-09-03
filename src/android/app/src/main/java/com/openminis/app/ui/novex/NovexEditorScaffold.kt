package com.openminis.app.ui.novex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/** Shared edit-page chrome. Domain forms remain supplied by each page. */
@Composable
internal fun NovexEditorScaffold(
    title: String,
    loaded: Boolean,
    canSave: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    saveContainerColor: Color = NovexColors.Primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    NovexDetailScaffold(
        title = title,
        onBack = onBack,
        actions = {
            IconButton(onClick = onPreview, enabled = loaded && canSave) {
                Icon(painterResource(R.drawable.ic_phosphor_eye), contentDescription = "预览草稿")
            }
        },
        bottomBar = {
            Button(
                onClick = onSave,
                enabled = loaded && canSave && !saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = saveContainerColor,
                    contentColor = Color.White,
                    disabledContainerColor = saveContainerColor.copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f),
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(if (saving) "保存中" else "保存") }
        },
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            content()
        }
    }
}

/** Draft previews and saved pages share their domain renderer; only the chrome differs. */
@Composable
internal fun NovexDraftPreviewScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    NovexDetailScaffold(
        title = title,
        onBack = onBack,
        actions = {
            NovexTopAction(
                icon = R.drawable.ic_phosphor_eye,
                contentDescription = "返回编辑",
                onClick = onBack,
            )
        },
        content = content,
    )
}

@Composable
internal fun NovexEditorSection(
    header: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            header,
            color = NovexColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
        Column(content = content)
        footer?.let {
            Text(
                it,
                color = NovexColors.SecondaryText,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun NovexEditorFoldRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = NovexColors.Text)
        Text(if (expanded) "收起" else "展开", color = NovexColors.SecondaryText)
    }
}

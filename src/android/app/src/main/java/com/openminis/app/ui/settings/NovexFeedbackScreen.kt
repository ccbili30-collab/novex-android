package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.openExternalUrl

private const val NOVEX_GROUP_QQ = "1109575872"
private const val NOVEX_AUTHOR_QQ = "2310212103"
private const val NOVEX_ISSUES_URL = "https://github.com/ccbili30-collab/novex-android/issues"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovexFeedbackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QQ 反馈与交流") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "反馈您在使用过程中遇到的问题和优化建议。可以大胆提出新功能，也可以联系作者定制私人界面皮肤。 注释 1",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            FeedbackRow(Icons.Outlined.Groups, "Novex（诺文）交流群", NOVEX_GROUP_QQ) {
                copy(NOVEX_GROUP_QQ)
            }
            HorizontalDivider()
            FeedbackRow(Icons.Outlined.Person, "作者个人 QQ", NOVEX_AUTHOR_QQ) {
                copy(NOVEX_AUTHOR_QQ)
            }
            HorizontalDivider()
            FeedbackRow(
                Icons.Outlined.ReportProblem,
                "GitHub Issues（GitHub 问题反馈页）",
                "Novex（诺文）问题反馈",
                showCopy = false,
            ) {
                openExternalUrl(context, NOVEX_ISSUES_URL)
            }
        }
    }
}

@Composable
private fun FeedbackRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    showCopy: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (showCopy) Icons.Outlined.ContentCopy else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

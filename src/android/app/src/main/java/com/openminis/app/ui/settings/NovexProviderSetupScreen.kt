package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import java.util.UUID

/**
 * Novex only needs one OpenAI-compatible connection for its first usable run.
 * The complete upstream provider manager remains available internally, but it
 * is not used as the onboarding surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovexProviderSetupScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var apiBase by remember { mutableStateOf("https://api.deepseek.com") }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("deepseek-chat") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("填写三项即可开始", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Novex 使用 OpenAI 兼容接口，可连接 DeepSeek 和常见中转站。密钥只保存在这台设备上。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = apiBase,
                onValueChange = { apiBase = it; error = null },
                label = { Text("接口地址") },
                supportingText = { Text("DeepSeek 官方地址已替你填好；使用中转站时改成对方提供的地址。") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; error = null },
                label = { Text("API 密钥") },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                supportingText = { Text("通常以 sk- 开头；请勿把密钥发送给其他人。") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it; error = null },
                label = { Text("模型名称") },
                supportingText = { Text("例如 deepseek-chat；中转站用户请填写站点给出的模型名。") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    val cleanBase = apiBase.trim().trimEnd('/')
                    val cleanKey = apiKey.trim()
                    val cleanModel = modelId.trim()
                    when {
                        cleanBase.isEmpty() -> error = "请填写接口地址"
                        cleanKey.isEmpty() -> error = "请填写 API 密钥"
                        cleanModel.isEmpty() -> error = "请填写模型名称"
                        !cleanBase.startsWith("http://") && !cleanBase.startsWith("https://") ->
                            error = "接口地址需要以 https:// 或 http:// 开头"
                        else -> {
                            val instance = ProviderInstance(
                                id = UUID.randomUUID().toString(),
                                label = if (cleanBase.contains("deepseek.com")) "DeepSeek" else "OpenAI 兼容接口",
                                providerType = ProviderType.openAI,
                                credentialType = ProviderCredential.apiKey,
                                customBaseURL = cleanBase,
                                appendV1Suffix = !cleanBase.endsWith("/v1"),
                            )
                            providerRepository.addInstance(instance)
                            providerRepository.saveApiKey(instance.id, cleanKey)

                            val entry = ModelEntry(
                                providerInstanceId = instance.id,
                                baseModel = LLMModel(
                                    id = cleanModel,
                                    displayName = LLMModel.modelDisplayName(cleanModel),
                                    provider = instance.label,
                                ),
                                isCustom = true,
                            )
                            providerRepository.addEntry(entry)
                            val group = ModelGroup(
                                name = "默认模型",
                                memberEntryIds = mutableListOf(entry.id),
                            )
                            providerRepository.addGroup(group)
                            providerRepository.defaultPrimaryGroupId = group.id
                            onSaved()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并开始")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")))
                }) {
                    Text("前往 DeepSeek 获取密钥")
                }
            }
        }
    }
}

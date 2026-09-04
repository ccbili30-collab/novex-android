package com.openminis.app.ui.novex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.MinisApp
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexCreativeArtifactReader
import com.openminis.app.novex.domain.NovexWorkspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Composition-root bridge; feature pages only see the Novex domain interface. */
@Composable
fun rememberNovexWorkspace(): NovexWorkspace {
    val application = LocalContext.current.applicationContext as MinisApp
    return remember(application) { application.novexWorkspace }
}

/** Composition-root bridge for the read-only creative artifact seam. */
@Composable
fun rememberNovexCreativeArtifacts(): NovexCreativeArtifactReader {
    val application = LocalContext.current.applicationContext as MinisApp
    return remember(application) { application.creativeArtifactRepository }
}

/** One shared read path for generated images attached to content modules. */
@Composable
internal fun rememberNovexAttachedModuleImages(owner: NovexContentAddress?): Map<String, File> {
    val artifacts = rememberNovexCreativeArtifacts()
    var images by remember(owner) { mutableStateOf<Map<String, File>>(emptyMap()) }
    LaunchedEffect(owner, artifacts) {
        images = if (owner == null) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                runCatching { artifacts.attachedModuleImageFiles(owner) }.getOrDefault(emptyMap())
            }
        }
    }
    return images
}

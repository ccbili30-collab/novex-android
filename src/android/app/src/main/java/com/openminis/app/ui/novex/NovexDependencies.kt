package com.openminis.app.ui.novex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.MinisApp
import com.openminis.app.novex.domain.NovexWorkspace

/** Composition-root bridge; feature pages only see the Novex domain interface. */
@Composable
fun rememberNovexWorkspace(): NovexWorkspace {
    val application = LocalContext.current.applicationContext as MinisApp
    return remember(application) { application.novexWorkspace }
}

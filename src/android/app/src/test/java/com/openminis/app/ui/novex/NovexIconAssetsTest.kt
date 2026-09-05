package com.openminis.app.ui.novex

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.*
import org.junit.Test

class NovexIconAssetsTest {
    @Test fun everyShippedIconCanParseItsVectorPaths() {
        val getters = NovexIcons.javaClass.methods.filter {
            it.parameterCount == 0 && it.returnType == ImageVector::class.java
        }
        assertEquals(174, getters.size)
        getters.forEach { method ->
            val image = method.invoke(NovexIcons) as ImageVector
            assertTrue("${method.name} 没有图形路径", image.root.size > 0)
            assertEquals(256f, image.viewportWidth, 0f)
            assertEquals(256f, image.viewportHeight, 0f)
        }
    }
}

package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIHttpErrorDetailTest {
    @Test
    fun `authentication mapping retains status and upstream response`() {
        val provider = OpenAIProvider(apiKey = "test-key")
        val method = OpenAIProvider::class.java.getDeclaredMethod(
            "mapHttpError",
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }

        val error = method.invoke(
            provider,
            401,
            """{"error":{"message":"account route rejected","request_id":"req-42"}}""",
        ) as LLMError

        assertTrue(error is LLMError.InvalidApiKey)
        assertTrue(error.message.orEmpty().contains("401"))
        assertTrue(error.message.orEmpty().contains("account route rejected"))
        assertTrue(error.message.orEmpty().contains("req-42"))
    }
}

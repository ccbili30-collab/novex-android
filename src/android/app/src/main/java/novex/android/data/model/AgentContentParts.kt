package novex.android.data.model

import org.json.JSONObject
import java.util.Objects

/*
 * Typed content parts carried by agent-layer messages. The sealed members
 * mirror the tool_use / tool_result blocks providers put on the wire, so
 * the field set is fixed by the providers' protocols; what is ours is the
 * equality implementation: the two members holding byte arrays hand-write
 * equals/hashCode, because generated array identity would make two
 * structurally equal parts compare unequal.
 */

sealed class AgentContentPart {
    data class Text(val text: String) : AgentContentPart()

    data class ImageData(
        val data: ByteArray,
        val mimeType: String,
        // iSH-visible path the bytes were persisted to, when they were.
        val linuxPath: String? = null,
        // Substitute text the provider shows on the no-native-vision path.
        val noVisionPlaceholder: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            val twin = other as? ImageData ?: return false
            return mimeType == twin.mimeType &&
                linuxPath == twin.linuxPath &&
                noVisionPlaceholder == twin.noVisionPlaceholder &&
                data contentEquals twin.data
        }

        override fun hashCode(): Int = Objects.hash(data, mimeType, linuxPath, noVisionPlaceholder)
    }

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject,
        // Opaque signature captured at tool-call time and replayed on the
        // matching historical call; null for every non-Gemini provider.
        val thoughtSignature: String? = null,
    ) : AgentContentPart()

    data class ToolResult(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        val imageData: ByteArray? = null,
        val imageMimeType: String? = null,
        // Where the image bytes live on the Linux side, for budgeting.
        val imageLinuxPath: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            val twin = other as? ToolResult ?: return false
            if (id != twin.id || name != twin.name || content != twin.content) return false
            if (isError != twin.isError || imageMimeType != twin.imageMimeType) return false
            if (imageLinuxPath != twin.imageLinuxPath) return false
            return imageData contentEquals twin.imageData
        }

        override fun hashCode(): Int = Objects.hash(
            id, name, content, isError, imageData, imageMimeType, imageLinuxPath,
        )
    }
}

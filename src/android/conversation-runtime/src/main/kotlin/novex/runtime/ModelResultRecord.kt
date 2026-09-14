package novex.runtime

import novex.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object ModelResultRecord {
    fun encode(result:ModelResult):JSONObject=when(result) {
        is ModelResult.Reply->JSONObject().put("kind","reply").put("text",result.text)
            .put("inputTokens",result.inputTokens?:JSONObject.NULL).put("outputTokens",result.outputTokens?:JSONObject.NULL)
        is ModelResult.Partial->JSONObject().put("kind","partial").put("text",result.text).put("reason",result.reason)
        is ModelResult.ToolsRequested->JSONObject().put("kind","tools_pending").put("text",result.text).put("calls",JSONArray(result.calls.map {
            JSONObject().put("id",it.id).put("name",it.name).put("arguments",it.arguments)
        }))
        is ModelResult.Rejected->JSONObject().put("kind","rejected").put("status",result.status).put("category",result.category)
            .put("retryAfter",result.retryAfter?:JSONObject.NULL)
        is ModelResult.InvalidResponse->JSONObject().put("kind","invalid_response").put("reason",result.reason)
        is ModelResult.NotSent->JSONObject().put("kind","not_sent").put("capacity",result.capacity.toString())
        ModelResult.NetworkFailure->JSONObject().put("kind","network_failure")
        ModelResult.TimedOut->JSONObject().put("kind","timed_out")
        ModelResult.Cancelled->JSONObject().put("kind","cancelled")
    }
}

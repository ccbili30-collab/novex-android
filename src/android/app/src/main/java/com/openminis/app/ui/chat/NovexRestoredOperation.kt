package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.NovexOperationRecord
import com.openminis.app.novex.domain.NovexOperationStatus

/** A missing receipt is never presented as successful execution. */
internal fun restoredOperationStatus(record: NovexOperationRecord?): ToolBlockStatus = when (record?.status) {
    NovexOperationStatus.WAITING, NovexOperationStatus.APPROVED -> ToolBlockStatus.PENDING
    NovexOperationStatus.RUNNING -> ToolBlockStatus.RUNNING
    NovexOperationStatus.SUCCEEDED -> ToolBlockStatus.SUCCESS
    NovexOperationStatus.FAILED -> ToolBlockStatus.FAILED
    else -> ToolBlockStatus.CANCELLED
}

internal fun restoredOperationNotice(record: NovexOperationRecord?): String = when (record?.status) {
    NovexOperationStatus.WAITING -> "等待批准，尚未执行"
    NovexOperationStatus.APPROVED -> "已批准，等待恢复执行"
    NovexOperationStatus.DENIED -> "你已拒绝此操作，未执行"
    NovexOperationStatus.RUNNING -> "操作正在执行"
    else -> "执行已中断，尚无完成回执；继续前需要核对结果"
}

package com.openminis.app.data.model

/** 界面与请求共用同一个容量解析，显式上游数据及用户覆盖优先。 */
internal fun inferContextWindowTokens(model:LLMModel):Int = model.contextWindowTokens

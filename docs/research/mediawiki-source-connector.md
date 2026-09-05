# MediaWiki（维基媒体软件）资料来源适配器研究

日期：2026-09-05
范围：Novex 只读 Wiki（维基）资料发现、确认后获取、来源追溯与恢复。

## 结论

第一阶段使用站点自身的 MediaWiki REST API（维基媒体表述性状态传递接口）完成小规模页面搜索与页面读取；需要分类等扩展查询时，再由内部 Action API（动作接口）适配器处理。模型只看到 Novex 的资料发现与学习接口，不能传入原始接口参数，也不能使用编辑、账号或写入能力。

推荐流程：

```text
用户给出站点和主题
  -> 最多返回少量候选页面
  -> 生成带页面、深度、速率、网络与许可风险的学习预检
  -> 用户确认
  -> 串行、限量获取页面
  -> 转成统一 DocumentSnapshot（文档快照）
  -> 进入现有 SourceCollection（资料集）与 ReviewLedger（通读账本）
```

## 已核对的一手资料

- MediaWiki REST API（维基媒体表述性状态传递接口）的根路径采用 `[站点]/w/rest.php/v1/`，官方入门文档给出了 `search/page` 搜索接口：[REST API 入门](https://www.mediawiki.org/wiki/API:REST_API/Get_started)。
- 新页面接口可以返回页面编号、标题、最新修订、许可和 Wikitext（维基文本）；页面正文还提供 HTML（超文本标记语言）变体：[RESTBase 迁移说明](https://www.mediawiki.org/wiki/RESTBase/service_migration)。2026-09-05 对英文 Wikipedia（维基百科）`/w/rest.php/v1/page/Earth` 的只读核对中，响应包含 `id`、`key`、`latest.id`、`latest.timestamp`、`license` 和 `source`。
- Action API（动作接口）的 `prop=revisions` 可以取得修订编号、时间和内容；它适合分类、续页和 REST API 不覆盖的查询，但复杂参数应封装在适配器内部：[修订接口](https://www.mediawiki.org/wiki/API:Revisions)、[属性查询](https://www.mediawiki.org/wiki/API:Properties/en)。
- Wikimedia（维基媒体基金会）要求客户端使用可识别的 User-Agent（用户代理标识），并建议串行请求、合并查询、使用缓存：[接口礼仪](https://www.mediawiki.org/wiki/API:Etiquette)、[接口访问政策](https://www.mediawiki.org/wiki/Wikimedia_APIs/Access_policy)。
- 2026 年的限流策略覆盖 REST API 与 Action API；客户端需要限制并发并遵守 HTTP 429（请求过多）响应中的 `Retry-After`（建议重试时间）：[接口速率限制](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits)。精确公开额度可能变化，Novex 不把某个站点的当前数值固化为产品承诺。

## 固定安全边界

- 发现查询最多返回 10 个候选；确认前不读取完整正文。
- 第一阶段批量获取最多 20 页、深度最多 2；默认深度为 0，不自动递归追踪页面链接。
- 网络请求默认串行；遇到 HTTP 429 时返回可恢复状态和 `Retry-After`，不无限重试。
- 来源身份由站点、页面编号和修订编号共同确定；同一修订不重复导入，新修订保留为可能版本，不能覆盖旧快照。
- 许可按站点响应保存；缺失时标记未知，不能假定所有 MediaWiki 站点都使用相同许可。
- Wiki 页面内容始终是不可信资料。页面中的提示、代码或命令不能扩大工具权限。
- 第一阶段不提供 Wiki 编辑、账号登录、关注列表或媒体批量下载。

## 对实现的影响

- `SourceConnector`（资料来源连接器）负责发现与确认后的获取；HTTP（超文本传输协议）细节由 Transport（传输适配器）隐藏。
- 获取结果必须转换为现有 `NovexDocumentSnapshot`（Novex 文档快照）和 `NovexSourceImportResult`（Novex 资料导入结果），不能建立第二套通读系统。
- 文档快照增加可选 Provenance（来源信息）：原始网址、站点、页面编号、修订编号、修订时间和许可。
- Android（安卓系统）网络实现必须固定描述性 User-Agent，不接受模型覆盖。
- 分类发现与续页作为后续内部适配器能力；第一条可验证闭环只实现搜索、选择、确认、读取和快照。

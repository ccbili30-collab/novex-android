# 任务书：卡片编辑器两处打磨（阅读布局文案 + 简介嵌套 JSON 解包）

日期：2026-09-28　分支：card-editor-polish　目标：next

## 背景

用户看编辑角色「⋮」菜单截图反馈：
1. 「默认连续阅读 / 默认模块翻页」——「太不清晰了，用户看不懂」。
2. 「简介」栏显示整段原始 JSON（`{"profileSchema":...}`）——导入的酒馆风格卡把
   另一份 profile JSON 整个塞进 summary 字符串，导入没解包。用户裁决：「需要
   json 解析」。

## 决议

- **文案**（用户中途裁决：「直接叫：滚动模式/翻页模式」）：菜单两项改为
  「滚动模式」「翻页模式」。只改文案，行为不变
  （`setReadingLayout(CONTINUOUS/PAGED)`）。
- **简介解包**：`CharacterVersionProfile.fromJson` 对 summary 做防御性解包——
  trim 后以 `{` 开头则尝试解析内层 JSON，取其 `description` 作为简介；解析
  失败/无 description 原样保留（fail-open，绝不让坏卡变导入失败）。
  写回（toJson）自然写干净文本。

## 验收

- 菜单文案更新；行为不变；
- 嵌套 JSON summary 解包出 description；普通 summary 原样；坏 JSON 原样；
- CI 绿。

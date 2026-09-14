# Novex 卡片格式规范

本文件是**核心文档**：原生 Novex 卡片导出/导入格式的唯一规范。实现位于
`src/android/content-storage`（`ExchangeLab.kt`、`CardStructureCodec.kt`、`CardFiles.kt`），
数据模型位于 `src/android/content-core`（`ContentDocument.kt`）。**改格式必须同步改本文，
并保证 `CardRoundTripReconciliationTest` 通过。**

更新：2026-09-14（对应交换包 version 1/2 实现）

## 设计原则

1. **结构与正文分离**：结构是 JSON，正文和图片字节是独立载荷，结构里只放 `ContentRef` 引用，
   不内嵌 base64。导出包因此可以流式复制大正文。
2. **宁可拒绝，不可静默丢弃**：解析时对未知字段、未知块类型、清单不匹配一律报错，
   不猜、不丢、不降级。
3. **完整性可校验**：每个载荷条目登记 `size` 和 `sha256`，读取时逐条核对。

## 导出包（交换包）

导出产物是一个 ZIP 压缩包，内含两类条目：

```text
world.novex.zip
├── structure.json          ← 结构与清单（始终叫这个名字）
├── 图片/0000-世界.png       ← 载荷（正式导出，version 2，人可读命名）
├── 正文/0001-世界-主线.md
└── 扩展/0002-内容.bin
```

- **version 2（正式导出，`CardFiles.export` 使用）**：载荷放在 `图片/`、`正文/`、`扩展/`
  三个目录下，文件名 = 四位序号 + `-` + 人类可读标签（卡名、模块名等，非法字符替换为 `_`，
  截断到 100 字符）。
- **version 1（机器格式）**：载荷放在 `contents/0`、`contents/1`… 下。
  读取两种都接受，正式导出只用 version 2。

### structure.json

```json
{
  "experiment": "novex-content-exchange",
  "version": 2,
  "card": { ...卡片结构，见下... },
  "contents": {
    "<ContentRef 值>": { "path": "图片/0000-世界.png", "size": 1234, "sha256": "<64 位十六进制>" }
  }
}
```

`contents` 的键集合必须**恰好等于**卡片结构里引用到的全部 `ContentRef`（多一个少一个都拒收）。
包内不允许出现清单之外的多余条目，也不允许重名条目。

### 卡片结构（card 对象）

```json
{
  "id": "world",
  "kind": "WORLD",                          // WORLD 或 CHARACTER，只有这两种
  "name": "世界",
  "appearance": {
    "avatar": "资源id 或 null",              // 必须引用本卡 resources
    "cover":  "资源id 或 null",
    "readingLayout": "PAGED"                 // PAGED 或 CONTINUOUS；缺省视为 PAGED
  },
  "extensions": { "任意键": "<ContentRef>" }, // 未知扩展保留原文引用，不解释不改写
  "resources": [
    { "id": "资源id", "content": "<ContentRef>", "mediaType": "image/png" }
  ],
  "modules": [ ...模块树，数组顺序即模块顺序... ],
  "characters": [ ...世界内角色，每项是完整的卡片结构（可递归嵌套）... ]
}
```

### 模块（modules 数组元素）

```json
{
  "id": "模块id",
  "name": "模块名",
  "layout": "VERTICAL",                      // VERTICAL 竖向 / HORIZONTAL 横向；缺省 VERTICAL
  "blocks": [
    { "kind": "text",  "id": "块id", "content": "<ContentRef>" },
    { "kind": "image", "id": "块id", "resource": "资源id", "caption": "<ContentRef 或 null>" }
  ],
  "tags": ["主线", "重要"],                   // 可选；不能有空串或重复
  "use": {                                    // 可选；携带规则
    "kind": "keywords", "words": ["城门"], "caseSensitive": false, "requireAll": true
  },
  "children": [ ...子模块，可递归嵌套... ],    // 可选
  "characters": ["角色卡id"]                  // 可选；角色展示位，必须指向本世界 internalCharacters 且不重复
}
```

`use` 三种：`{"kind":"always"}`、`{"kind":"manual"}`、`{"kind":"keywords", words, caseSensitive, requireAll}`。

### 结构校验规则（导入时强制）

- 所有对象编号（卡、模块、块、资源）在同一内容树内唯一且非空；
- 角色卡不能再包含内部角色；世界内部只能包含角色卡；
- 头像/封面/图片块必须引用**本卡**的资源；角色展示位必须定位**本世界**的角色；
- 解析端做**严格字段检查**：字段不完整或出现未支持字段直接报错（"不能静默丢弃"）。

## 导入语义（重要）

通过 `CardFiles.prepare` 导入交换包时：

1. 包内载荷先校验（大小 + SHA-256）再转存到本地存储，转存失败不产生卡片；
2. **对象编号和内容引用会重新分配**（防止与库内已有对象冲突）——这是设计行为，
   不是信息丢失；
3. 导入产生**草稿**（`ChangeSource.IMPORT`），用户确认提交后才归库；
4. 草稿未提交前不能导出正式版本（`CardFiles.export` 只导出已保存版本）。

### 往返保证（对账基线）

导出 → 导入 → 再导出，以下内容**必须逐项一致**（编号除外）：

- 卡类（kind）、卡名；
- 模块顺序、嵌套关系、layout、tags、use 规则、角色展示位；
- 块顺序与类型（text/image）、图片说明（caption）的有无；
- 每个正文/图片/扩展载荷的**字节内容**（按 SHA-256 对账）；
- 资源数量与 mediaType、扩展键集合、外观语义（头像/封面指向、阅读布局）；
- 世界内角色递归完整保留。

以上由 `content-storage` 的 `CardRoundTripReconciliationTest` 自动守护；改格式前先改本文，
再改测试，再改代码。

## 非 Novex 原生文件的导入

- **PNG 卡**（含角色原文的 PNG）和 **UTF-8 纯文本**：整体原文进入自由文本模块，
  不强行拆成"概述""姓名"等字段；
- 其他无法解析的格式：报错，不创建卡片、不留半成品。

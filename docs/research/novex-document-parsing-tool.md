# Novex Android（安卓系统）文档解析工具研究

> 研究日期：2026-09-05  
> 范围：DOCX（Word 开放 XML 文档）、DOC（旧版 Word 二进制文档）、PDF（便携式文档格式）、TXT（纯文本）、Markdown（轻量标记语言）与 HTML（超文本标记语言）的读取、结构提取和面向 AI（人工智能）的按需访问。  
> 证据边界：只采用格式标准、平台文档、项目官方文档、官方源代码仓库和官方制品仓库。本文没有修改产品源码。

## 结论摘要

Novex 不应再把“解析整份文件并截断后塞进提示词”当作文档工具。应先建立稳定的标准工具接口和 `DocumentIR（统一文档中间表示）`，再把不同解析引擎放在接口后面。模型只读取目录、搜索结果和所需章节；图片通过资源编号单独取得。这样才能同时解决长文档成本、解析结果不可定位、旧 Minis（原版框架）文件工具不可靠，以及以后替换底层实现的问题。

推荐路线如下：

1. **先建立标准文档工具和统一中间表示**：提供检查、目录、分段读取、搜索和资源读取，不向 AI（人工智能）暴露任意文件系统或解析器内部对象。
2. **DOCX（Word 开放 XML 文档）的长期主实现采用平台 `ZipFile（压缩包读取器） + XmlPullParser（拉取式 XML 解析器）`**：零新增解析依赖，按包内部件和节点增量读取，最适合 Novex 已有的模块化数据和长文档压力。
3. **Java Mammoth（Java 版 Mammoth 语义转换器）适合作为快速验证桥梁和语义对照器**：官方制品约 254 KB（千字节），无运行时依赖，覆盖标题、列表、表格、图片、链接、脚注、尾注和文本框；但官方没有承诺 Android（安卓系统）兼容性，而且它会先建立文档模型并生成 HTML（超文本标记语言），不是真正流式。因此必须先做 Android（安卓系统）仪器测试和长文压力测试，不能直接视为最终答案。
4. **Apache POI（阿帕奇 Office 文档库）暂时保留为兼容回退和测试对照，不再作为默认长期引擎**：项目当前 Android（安卓系统）阴影包约 19 MB（兆字节），对象模型重，官方也明确说明 XWPF（Word 开放 XML 高层接口）并不完整。
5. **DOC（旧版 Word 二进制文档）不放进基础安装包**：设备端提示另存为 DOCX（Word 开放 XML 文档）；需要最高兼容性时交给可选的 LibreOffice（自由办公套件）无界面外部适配器。若以后必须完全离线支持，再把 HWPF（旧 Word 二进制接口）放入可选动态模块。
6. **PDF（便携式文档格式）按页处理**：Android 15 / API 35（应用程序编程接口级别 35）优先用系统文本接口，Android 11—14 / API 30—34 使用 `PdfRendererPreV（旧系统兼容 PDF 渲染器）`，API 26—29 暂时保留现有 `pdfbox-android（安卓移植版 PDFBox）` 回退。扫描件必须明确标记“需要 OCR（光学字符识别）”，不能假装已经提取正文。
7. **TXT（纯文本）直接流式读取；Markdown（轻量标记语言）先按行建立标题与区段索引；HTML（超文本标记语言）使用 jsoup（Java HTML 解析器）并强制清洗、禁止联网。**

## 一、当前实现核对与实际问题

### 1. 已有能力

项目的 Android（安卓系统）最低版本为 API 26（应用程序编程接口级别 26）。当前文档链路主要位于：

- `DocumentTextExtractor.kt（文档文本提取器）`
- `DocxPoiTextExtractor.kt（基于 POI 的 DOCX 文本提取器）`
- `AttachmentPromptMetadata.kt（附件提示词元数据）`

当前 DOCX（Word 开放 XML 文档）主路径使用 `poishadow-all-5.2.5-4.jar（经过 Android 兼容改写的 POI 聚合包）`，本地制品约 19 MB（兆字节）。它已经尝试提取正文、表格、页眉页脚、超链接、批注、脚注、尾注和图片存在信息，并额外扫描文本框与修订相关 XML（可扩展标记语言）。

当前回退路径会直接打开 DOCX（Word 开放 XML 文档）压缩包，但它先把 XML（可扩展标记语言）部件全部读成字符串，再用 DOM（文档对象模型）完整建树；这不是流式解析，长文档会同时持有压缩数据、解压字符串和节点树。

PDF（便携式文档格式）当前使用 `pdfbox-android 2.0.27.0（安卓移植版 PDFBox 2.0.27.0）`，本地 AAR（安卓归档包）约 3.1 MB（兆字节）。旧 `.doc（Word 二进制文档）` 当前明确不支持。

测试夹具已经覆盖 Word（微软文字处理器）、LibreOffice（自由办公套件）、WPS（金山办公套件）和 Google Docs（谷歌文档）来源，以及标题、列表、表格、链接、页眉页脚、脚注尾注、文本框与修订。这些夹具应继续作为迁移对照集。

### 2. “文档解析很差”不只是解析器问题

现有提取器允许生成最多 2,000,000 个字符的 `.extracted.md（提取后的 Markdown 文档）`，但对话提示词每个文件只内联 48,000 个字符，所有文件合计只内联 96,000 个字符。剩余内容只通过 `/var/minis/...（Minis 私有路径）` 告知模型自行读取。

因此当前体验问题至少有四层：

1. 长文被固定截断，模型看不到后半部分；
2. 结果只有一整块文本，没有目录、章节、表格坐标或稳定来源锚点；
3. 模型必须依赖已经不稳定的 Minis（原版框架）文件与命令工具读取剩余内容；
4. 图片、脚注、表格等结构在转成单一 Markdown（轻量标记语言）后难以精确回查。

仅仅把 Apache POI（阿帕奇 Office 文档库）换成另一套库，不能解决这四个问题。首先要更换的是**文档访问模型**。

## 二、标准工具层与统一中间表示

### 1. 面向 AI（人工智能）的标准工具

建议把以下能力作为 Novex 自有标准工具，解析引擎不出现在参数中：

| 工具 | 用途 | 关键输出 |
|---|---|---|
| `document.inspect（文档检查）` | 识别格式、加密状态、页数或结构规模、可用能力 | 文档编号、类型、元数据、能力、警告 |
| `document.outline（文档目录）` | 返回标题、章节、页或工作表式目录 | 节点编号、层级、标题、范围、游标 |
| `document.read（文档读取）` | 按节点、章节、页、游标或字符预算读取 | 有界内容、来源锚点、下一页游标 |
| `document.search（文档搜索）` | 在规范化索引中检索关键词或短语 | 命中片段、节点编号、来源位置 |
| `document.get_asset（文档资源获取）` | 读取某张图片或附件 | 资源编号、媒体类型、尺寸、受控内容 |

后续如需转换或保存，再新增 `document.export（文档导出）`，不要让读取工具顺便写文件。

模型的标准流程应是：先检查，再看目录；需要时搜索，然后只读取相关章节。对话里显示“本轮查看了某文档的某章节/表格/图片”，来源锚点也能直接支持用户此前要求的引用提示。

### 2. `DocumentIR（统一文档中间表示）`

解析结果不再只保存一份巨大的 Markdown（轻量标记语言），而是保存可分页、可搜索、可追踪来源的节点流：

```text
Document（文档）
├─ Heading（标题：级别、文本、来源锚点）
├─ Paragraph（段落）
│  └─ Run（文本片段：样式、链接、批注或修订标记）
├─ List / ListItem（列表 / 列表项）
├─ Table / Row / Cell（表格 / 行 / 单元格：含合并范围）
├─ Image（图片：资源编号、说明、锚点）
├─ Footnote / Endnote（脚注 / 尾注）
├─ PageBreak（分页符）
└─ Warning / UnknownObject（警告 / 未知对象）
```

统一中间表示应满足：

- 每个节点有稳定编号和原始来源锚点；
- 内容可按节点边界分页，不能重复或遗漏字符；
- 未识别对象保留类型和位置警告，不能静默丢弃；
- 图片二进制不进入正文或提示词，以资源编号引用；
- 原始文件与解析缓存按 SHA-256（256 位安全哈希算法）关联；
- 规范化索引与解析引擎解耦，换引擎后工具接口不变。

## 三、DOCX（Word 开放 XML 文档）方案比较

DOCX（Word 开放 XML 文档）是基于 OPC（开放打包约定）的 ZIP（压缩归档）包，由内容部件、关系部件和 `[Content_Types].xml（内容类型清单）` 组成。正文的基础层级是 `document → body → p → r → t（文档 → 正文 → 段落 → 文本片段 → 文本）`。这使按部件直接解析成为标准允许、而且适合移动端的路线。[ECMA-376（Office 开放 XML 标准）](https://ecma-international.org/publications-and-standards/standards/ecma-376/)、[微软 Open XML（开放 XML）概览](https://learn.microsoft.com/en-us/office/open-xml/about-the-open-xml-sdk)、[微软 WordprocessingML（文字处理标记语言）结构说明](https://learn.microsoft.com/en-us/office/open-xml/word/how-to-open-and-add-text-to-a-word-processing-document)

### 方案 A：直接解析 ZIP（压缩归档）与 XML（可扩展标记语言）

Android（安卓系统）原生提供 `XmlPullParser（拉取式 XML 解析器）`，它按开始标签、文本和结束标签逐事件推进，适合受限设备和增量处理；`android.util.Xml（安卓 XML 工具）` 可直接从输入流创建解析器。[Android XmlPullParser（安卓拉取式 XML 解析器）](https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser)、[Android Xml（安卓 XML 工具）](https://developer.android.com/reference/android/util/Xml)

优点：

- 新增依赖接近零；
- 可按 `document.xml（正文）`、`styles.xml（样式）`、`numbering.xml（编号）`、脚注、尾注、批注和关系部件分别读取；
- 可以直接发出 `DocumentIR（统一文档中间表示）` 节点，不必先建立完整文档对象；
- 能在章节、段落或表格边界及时落盘并释放内存；
- 容易执行压缩包展开大小、关系目标和节点深度限制。

代价：

- 需要自己实现样式继承、编号列表、合并单元格、图片关系、脚注引用和浮动对象等映射；
- 对绘图、域代码、嵌入对象和兼容扩展必须明确降级并给出警告；
- 需要以已有多来源夹具长期防回归。

判断：**最适合长期生产实现**。需要避免复制当前“整部件字符串 + DOM（文档对象模型）”的回退写法；真正收益来自 `ZipFile（压缩包读取器） + 输入流 + XmlPullParser（拉取式 XML 解析器） + 节点落盘`。

### 方案 B：Apache POI（阿帕奇 Office 文档库）

官方把 XWPF（Word 开放 XML 高层接口）描述为“核心稳定但并不完整”，部分能力需要退回 XMLBeans（XML 绑定对象）底层；文本提取器可覆盖段落、表格和页眉等。旧格式 HWPF（旧 Word 二进制接口）则处于缺少维护者的状态。[Apache POI 文档组件](https://poi.apache.org/components/document/index.html)、[Apache POI XWPF 指南](https://poi.apache.org/components/document/quick-guide-xwpf.html)、[Apache POI 组件总览](https://poi.apache.org/components/)

官方 Maven（Java 制品仓库）中 5.5.1 版本的压缩 JAR（Java 归档包）约为：`poi` 3.01 MB（兆字节）、`poi-ooxml` 2.05 MB、`poi-ooxml-lite` 6.00 MB，三者合计约 11.05 MB，尚未计算 XMLBeans（XML 绑定库）、日志等传递依赖以及 Android（安卓系统）兼容处理。[Apache POI 官方下载页](https://poi.apache.org/download.cgi)、[poi 5.5.1 官方制品](https://repo.maven.apache.org/maven2/org/apache/poi/poi/5.5.1/)、[poi-ooxml 5.5.1 官方制品](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/5.5.1/)、[poi-ooxml-lite 5.5.1 官方制品](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml-lite/5.5.1/)

它会建立较完整的 Java（爪哇语言）对象图，使用方便但内存随复杂文档增长；标准发行也不是 Android（安卓系统）优先设计。许可证为 Apache-2.0（阿帕奇 2.0 许可证）。[Apache POI 法律与许可证](https://poi.apache.org/legal.html)

判断：**保留作迁移期兼容回退和语义对照，不建议继续作为基础安装包的默认解析器**。

### 方案 C：Apache Tika（阿帕奇内容检测与提取框架）

Tika（内容检测与提取框架）的 `Parser（解析器）` 接收输入流，并以 SAX（简单 XML 应用程序编程接口）事件输出 XHTML（可扩展超文本标记语言），接口本身适合流式消费。但 Word（文字处理）实际仍转交 POI（Office 文档库），PDF（便携式文档格式）仍转交 PDFBox（PDF 工具库）；OLE2（对象链接与嵌入复合文档）等输入还可能先写入临时文件。[Apache Tika 解析器接口](https://tika.apache.org/3.3.2/parser.html)、[Apache Tika 支持格式与底层库](https://tika.apache.org/3.3.2/formats.html)

官方说明标准解析器包会带来大量传递依赖和潜在版本冲突；一体化 `tika-app（Tika 应用包）` 3.3.2 官方制品约 66.98 MB（兆字节），这个数字只用于说明依赖量级，并不等于最终 APK（安卓应用安装包）增量。[Apache Tika 入门与依赖说明](https://tika.apache.org/3.3.2/gettingstarted.html)

判断：**适合服务器或桌面外部文档网关，不适合 Novex Android（安卓系统）基础安装包**。它统一的是检测入口，不会消除 POI（Office 文档库）与 PDFBox（PDF 工具库）的成本。

### 方案 D：docx4j（DOCX Java 对象模型库）

docx4j（DOCX Java 对象模型库）基于 JAXB（Java XML 绑定架构）建立完整 OOXML（Office 开放 XML）对象模型，支持读取、写入以及 HTML（超文本标记语言）和 PDF（便携式文档格式）输出。当前官方说明要求 Java 11（爪哇语言 11）以上，并且必须选择 JAXB（Java XML 绑定架构）实现。[docx4j 官方仓库](https://github.com/plutext/docx4j)

11.5.3 官方制品中，`docx4j-core（核心包）` 与 `docx4j-openxml-objects（开放 XML 对象包）` 压缩后合计约 6.16 MB（兆字节），尚未包含 JAXB（Java XML 绑定架构）实现和其他传递依赖。它的完整对象模型适合编辑、模板和转换，但对移动端只读抽取而言层级过深、内存成本偏高。许可证为 Apache-2.0（阿帕奇 2.0 许可证）。

判断：**不作为设备端基础抽取器；如以后做服务器端高保真 DOCX（Word 开放 XML 文档）编辑，可重新评估**。

### 方案 E：Mammoth（语义 DOCX 转换器）

Mammoth.js（JavaScript 版 Mammoth）目标是把 DOCX（Word 开放 XML 文档）转换为干净的语义 HTML（超文本标记语言），而不是复刻字体、边框和分页。官方列出的支持包括标题、列表、表格（不保留边框样式）、脚注尾注、图片、链接、文本框和批注；官方同时强调输出不做消毒，复杂或恶意输入可能消耗过多处理器与内存，应放在隔离环境并设置限制。[Mammoth.js 官方仓库](https://github.com/mwilliamson/mammoth.js/)

Java Mammoth（Java 版 Mammoth）1.12.1 使用 Java 8（爪哇语言 8），BSD-2-Clause（二条款 BSD 许可证），官方 `pom.xml（项目对象模型配置）` 没有运行时依赖；官方 Maven（Java 制品仓库）JAR（Java 归档包）约 254,066 字节。[Java Mammoth 官方仓库](https://github.com/mwilliamson/java-mammoth)、[Java Mammoth 1.12.1 官方制品](https://repo.maven.apache.org/maven2/org/zwobble/mammoth/mammoth/1.12.1/)

JavaScript（爪哇脚本）版本需要 WebView（网页视图）或脚本运行时桥接，增加部署面和隔离成本；Java（爪哇语言）版本更值得设备端试验。但两者都以完整转换为主，不提供 Novex 所需的稳定章节游标和真正增量节点流。官方也没有承诺 Android（安卓系统）兼容性。

判断：**Java 版适合做小体积快速原型和语义对照器，但须通过 Android（安卓系统）、R8（安卓代码压缩器）和超长文仪器测试；不把它直接等同于最终流式架构**。若使用 Mammoth（语义 DOCX 转换器），应生成 HTML（超文本标记语言）再转 `DocumentIR（统一文档中间表示）`；其 Markdown（轻量标记语言）直出能力在官方说明中已被弃用。

## 四、其他格式路线

### 1. DOC（旧版 Word 二进制文档）

DOC（旧版 Word 二进制文档）是 Word 97—2003 使用的二进制格式，建立在 OLE（对象链接与嵌入）复合文件上，格式复杂度和安全面远高于 DOCX（Word 开放 XML 文档）。[微软 MS-DOC（二进制 Word 格式）规范](https://learn.microsoft.com/en-us/openspecs/office_file_formats/ms-doc/ccd7b486-7881-484c-a137-51170af7cc22)、[微软 DOC 与开放 XML 格式关系说明](https://learn.microsoft.com/id-id/openspecs/office_file_formats/ms-doc/47d4949b-fca8-4811-bb71-5b70a9165235)

POI（Office 文档库）的 HWPF（旧 Word 二进制接口）能够读取部分内容，但官方将它标为缺少维护者；`poi + poi-scratchpad（POI 试验组件）` 的官方压缩制品约 4.92 MB（兆字节），仍未计传递依赖和 Android（安卓系统）兼容成本。

推荐：

- 基础安装包只识别并给出明确提示：“请另存为 DOCX（Word 开放 XML 文档）后重试”；
- 可联网或有桌面协作端时，交给 LibreOffice（自由办公套件）无界面适配器转换成 DOCX（Word 开放 XML 文档）或 HTML（超文本标记语言），然后走统一解析链路；
- 只有真实用户数据证明离线 DOC（旧版 Word 二进制文档）是刚需时，才把 HWPF（旧 Word 二进制接口）放入可选动态功能模块。

LibreOffice（自由办公套件）官方命令行支持 `--headless（无图形界面）`、`--convert-to（转换为）` 和 `--cat（输出文本）`，但它是完整桌面办公运行时，不适合嵌入 Android（安卓系统）基础包。它应只作为 Windows（视窗系统）、服务器或用户自有电脑上的外部适配器。其主体许可证为 MPL-2.0（Mozilla 公共许可证 2.0），并包含第三方许可证。[LibreOffice 启动与转换参数](https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html)、[LibreOffice 许可证](https://www.libreoffice.org/licenses/)

### 2. PDF（便携式文档格式）

PDF（便携式文档格式）首先描述页面上“画什么”，并不保证逻辑阅读顺序、标题层级或表格结构。PDFBox（PDF 工具库）官方也明确说明：文本顺序可能与视觉顺序不同，扫描 PDF（便携式文档格式）需要 OCR（光学字符识别），自定义字体编码可能导致乱码，而且真正抽取文本仍需解析整份文档结构。[PDFBox 常见问题](https://pdfbox.apache.org/3.0/faq.html)

Android（安卓系统）官方 `PdfRenderer.Page（PDF 页面渲染器页面）` 从 API 35（应用程序编程接口级别 35）提供 `getTextContents（获取文本内容）`；官方兼容库 `PdfRendererPreV（旧系统兼容 PDF 渲染器）` 把文本、图片和链接内容能力带到 API 30—34（应用程序编程接口级别 30—34）。官方文档明确把内容提取与 AI（人工智能）摘要列为使用场景。[Android PdfRenderer.Page（安卓 PDF 页面渲染器页面）](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)、[Android PDF 查看器与旧版兼容能力](https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer)

推荐：

- API 35（应用程序编程接口级别 35）以上：系统 `PdfRenderer（PDF 渲染器）`；
- API 30—34（应用程序编程接口级别 30—34）：官方 `PdfRendererPreV（旧系统兼容 PDF 渲染器）`；
- API 26—29（应用程序编程接口级别 26—29）：暂时保留现有 `pdfbox-android（安卓移植版 PDFBox）`，或明确声明有限支持；
- 统一输出为“页 → 文本块 / 图片 / 链接 / 警告”，不伪造标题和表格语义；
- 每页建立边界和来源坐标，允许按页读取；扫描件返回“需要 OCR（光学字符识别）”状态。

系统文档还建议把不受信任 PDF（便携式文档格式）的渲染放进独立隔离进程，构造和长耗时调用应在工作线程执行。[Android PdfRenderer（安卓 PDF 渲染器）安全说明](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)

### 3. TXT（纯文本）

不需要第三方依赖。使用带缓冲的输入流增量解码：

- 识别 UTF-8 / UTF-16（统一码转换格式 8 位 / 16 位）字节顺序标记；
- 默认 UTF-8（统一码转换格式 8 位），解码失败时允许用户选择编码；
- 按段落和行建立节点与游标，不一次性读取全部字符；
- 保留换行风格与原始字节偏移，便于稳定回查。

### 4. Markdown（轻量标记语言）

对 AI（人工智能）读取而言，第一阶段只需按行识别标题、代码围栏、列表、引用和分隔符，建立章节索引，零依赖且可流式。需要完整标准语法树时，可选 `commonmark-java（CommonMark Java 解析器）`：官方说明核心模块无依赖、体积小、有扩展模块，并以尽力而为方式支持 Android API 19（安卓应用程序编程接口级别 19）以上，许可证为 BSD-2-Clause（二条款 BSD 许可证）。它建立 AST（抽象语法树），因此完整解析仍会占用整文内存。[commonmark-java 官方仓库](https://github.com/commonmark/commonmark-java)、[CommonMark 0.31.2 规范](https://spec.commonmark.org/0.31.2/)

推荐：默认使用流式章节索引；只有渲染、严格表格扩展或格式转换时加载 `commonmark-java（CommonMark Java 解析器）`。

### 5. HTML（超文本标记语言）

使用 jsoup（Java HTML 解析器）解析和清洗，禁止脚本、事件属性、危险网址与外部资源自动访问。jsoup（Java HTML 解析器）建立 DOM（文档对象模型），不是流式；超大文件应先执行字节上限，再按主要区块切分或放入受限工作进程。许可证为 MIT（麻省理工许可证）。官方当前 Android（安卓系统）说明要求核心库脱糖并支持 API 21（应用程序编程接口级别 21）以上，符合 Novex 的 API 26（应用程序编程接口级别 26）下限。[jsoup 官方站点](https://jsoup.org/)、[jsoup HTML 清洗说明](https://jsoup.org/cookbook/cleaning-html/safelist-sanitizer)、[jsoup Android 兼容说明](https://jsoup.org/news/release-1.19.1)、[jsoup 许可证](https://jsoup.org/license)

HTML（超文本标记语言）导入必须默认离线：`img（图片）`、`link（链接）`、`iframe（内嵌框架）` 等只能成为待确认引用，不允许解析过程自行联网。

## 五、综合比较

下表的“制品规模”是官方仓库中的压缩 JAR（Java 归档包）或当前本地依赖大小，只用于量级比较；它不是经过 R8（安卓代码压缩器）后的最终 APK（安卓应用安装包）增量。速度和内存判断是由处理模型推断，官方没有提供同一 Android（安卓系统）设备上的横向基准。

| 候选 | Android（安卓系统）适配 | 压缩依赖量级 | 标题 / 表格 / 图片 / 脚注 | 流式与内存 | 许可证 | 建议 |
|---|---|---:|---|---|---|---|
| 直接 ZIP（压缩归档）+ `XmlPullParser（拉取式 XML 解析器）` | 优 | 约 0 新增 | 按 Novex 需要精确实现；复杂对象可警告降级 | 可按部件与节点真正增量 | Android（安卓系统）平台与项目自有代码 | **长期主实现** |
| Java Mammoth（Java 版语义转换器） | 可能良好，官方未承诺，需实机验证 | 约 254 KB（千字节），无运行时依赖 | 语义标题、列表、表格、图片、链接、脚注尾注较好 | 通常建立完整模型并生成 HTML（超文本标记语言），非真正流式 | BSD-2-Clause（二条款 BSD） | **快速原型与语义对照** |
| Apache POI（阿帕奇 Office 文档库） | 标准版非 Android 优先；项目依赖阴影包 | 项目当前约 19 MB（兆字节） | 覆盖广，但官方说明高层接口不完整 | 完整对象图，长文内存偏高 | Apache-2.0（阿帕奇 2.0） | 迁移期回退，不作默认长期引擎 |
| Apache Tika（阿帕奇内容提取框架） | 基础安装包不合适 | 一体包约 66.98 MB（兆字节）量级，且传递依赖多 | 格式广，实际委托 POI（Office 文档库）/ PDFBox（PDF 工具库）等 | 接口可流式，底层未必流式，可能落临时文件 | Apache-2.0（阿帕奇 2.0） | 服务器或桌面外部网关 |
| docx4j（DOCX Java 对象模型库） | Java 11（爪哇语言 11）+ JAXB（Java XML 绑定架构），移动端较重 | 核心与对象包约 6.16 MB（兆字节），未计传递依赖 | 读写与转换能力最完整的一档 | 完整 JAXB（Java XML 绑定架构）对象图 | Apache-2.0（阿帕奇 2.0） | 未来服务器端编辑，非基础抽取 |
| Mammoth.js（JavaScript 版语义转换器） | 需 WebView（网页视图）或脚本桥 | 未用同口径测量，不作数字判断 | 与 Mammoth（语义转换器）语义能力一致 | 通常需完整数组缓冲与 HTML（超文本标记语言）结果 | BSD-2-Clause（二条款 BSD） | 不作为原生主路径 |
| LibreOffice（自由办公套件）无界面模式 | 不嵌入 Android（安卓系统） | 完整桌面运行时 | 复杂 Office（办公文档）兼容性最高的一档 | 独立进程转换 | MPL-2.0（Mozilla 公共许可证 2.0）及第三方许可 | DOC（旧版 Word）与复杂文档外部适配器 |
| HWPF（旧 Word 二进制接口） | 可做但维护与兼容风险高 | `poi + scratchpad（试验组件）` 约 4.92 MB（兆字节），未计依赖 | 旧 DOC（Word 二进制文档）部分结构，官方称缺维护者 | 完整对象模型，非流式 | Apache-2.0（阿帕奇 2.0） | 仅在离线刚需成立后做可选模块 |
| Android PdfRenderer（安卓 PDF 渲染器） | API 35 原生；API 30—34 有官方兼容层 | 平台 / 官方兼容依赖 | 页文本、图片、链接；标题表格语义弱 | 适合逐页；文件仍需可寻址 | Android（安卓系统）平台 | 支持系统上的 PDF 主路径 |
| `pdfbox-android（安卓移植版 PDFBox）` | 已在项目使用 | 当前 AAR（安卓归档包）约 3.1 MB（兆字节） | 文本能力成熟，但受 PDF（便携式文档格式）本身限制 | 需解析整文结构，可按页输出 | Apache-2.0（阿帕奇 2.0） | API 26—29 临时回退 |
| TXT（纯文本）平台流 | 优 | 0 | 段落与行；无原生复杂结构 | 真正流式 | Android（安卓系统）平台 | 默认实现 |
| commonmark-java（CommonMark Java 解析器） | 官方尽力支持 Android API 19+（安卓应用程序编程接口级别 19+） | 核心约数百 KB（千字节）量级 | 标题、列表、链接等语义好，扩展可选 | 建立 AST（抽象语法树），非真正流式 | BSD-2-Clause（二条款 BSD） | 需要严格 Markdown（轻量标记语言）语义时按需加入 |
| jsoup（Java HTML 解析器） | API 21+（应用程序编程接口级别 21+），需核心库脱糖 | 约数百 KB（千字节）量级 | HTML（超文本标记语言）结构、表格、图片引用较好 | 完整 DOM（文档对象模型） | MIT（麻省理工） | HTML 导入主路径，必须清洗和禁网 |

## 六、安全与资源边界

解析器面对的是不受信任文件，工具层至少需要以下统一防护：

- 不在主线程解析；支持取消、超时和进度；
- 对原始文件大小、ZIP（压缩归档）展开总量、单部件大小、压缩比、节点数、嵌套深度、图片数量与像素总量设限；
- 拒绝 ZIP 路径穿越（压缩包内路径逃逸）和重复名称欺骗；
- 禁止 DTD（文档类型定义）和外部实体，禁止外部关系自动访问网络或文件；
- PDF（便携式文档格式）优先放在 Android（安卓系统）隔离进程；DOC（旧版 Word 二进制文档）转换只在受限外部适配器中运行；
- 密码保护或加密文档返回明确状态，不用“空文本”掩盖；
- 临时文件可追踪并及时清理；缓存按 SHA-256（256 位安全哈希算法）命中；
- 工具返回内容有硬预算，不能通过图片 Base64（六十四进制文本编码）或超长表格绕过上下文限制；
- 文档正文始终视为用户数据，不能被解释成工具指令或系统指令。

建议先用压力测试校准而不是把数字写死。可作为首轮工程目标、但不是标准或官方结论的预算包括：解析任务默认 10 秒、复杂文档最多 30 秒；峰值新增堆内存尽量不超过 64 MB（兆字节）或解压后文本体积的 2 倍，以较严格者为准；超过预算转入隔离任务、分段处理或给出明确失败原因。

## 七、测试驱动落地检查点

### 检查点 Q0：接口与夹具基线

先为 `DocumentIR（统一文档中间表示）`、游标和标准工具写失败测试：

- 同一文件重复解析得到稳定节点编号；
- `outline（目录） → read（读取） → next cursor（下一页游标）` 无重复、无漏字；
- `search（搜索）` 命中能回到确切节点与原文件位置；
- 未知对象、加密、损坏和被截断文件返回结构化警告；
- 解析不触发网络、模型、工具或其他外部副作用。

### 检查点 Q1：先替换对话内联模型

保留现有解析器，先把产物从单一 `.extracted.md（提取后的 Markdown 文档）` 接入文档工作区：

- 导入后建立文档记录、目录和索引；
- 提示词只提供标题、摘要、目录与文档编号；
- AI（人工智能）通过 `inspect（检查）/ outline（目录）/ read（读取）/ search（搜索）` 取所需内容；
- 对话消息显示实际引用的章节或页；
- 旧 `/var/minis（Minis 私有目录）` 路径不再是唯一补读通道。

这一步会直接改善体验，即使 DOCX（Word 开放 XML 文档）底层尚未替换。

### 检查点 Q2：DOCX（Word 开放 XML 文档）引擎 A/B（对照）

用同一套夹具同时验证：

- A：Java Mammoth（Java 版语义转换器）→ 清洗 HTML（超文本标记语言）→ `DocumentIR（统一文档中间表示）`；
- B：`ZipFile（压缩包读取器） + XmlPullParser（拉取式 XML 解析器）` → `DocumentIR（统一文档中间表示）`。

必须覆盖：中文长文、六级标题、嵌套列表、普通和合并表格、行内与浮动图片、超链接、页眉页脚、脚注尾注、分页、批注、修订、文本框和未知对象警告。加入 200,000 与 1,000,000 字符级别的合成长文，但不能只测纯文本，应包含大量小段落、表格和图片关系。

选择标准不是“哪一个测试先绿”，而是：语义正确率、稳定锚点、峰值内存、首个章节可用时间、取消响应和 APK（安卓应用安装包）增量。预计 Java Mammoth（Java 版语义转换器）更快形成可用原型，直接拉取解析器更适合最终长文生产。

### 检查点 Q3：PDF（便携式文档格式）、TXT（纯文本）、Markdown（轻量标记语言）与 HTML（超文本标记语言）

- PDF（便携式文档格式）：按系统版本选择引擎，统一输出页节点；加入文字型、双栏、表格外观、自定义字体编码和扫描件；
- TXT（纯文本）：验证字节顺序标记、超长行、混合换行、错误编码与取消；
- Markdown（轻量标记语言）：验证标题目录、代码围栏、列表、引用和扩展表格降级；
- HTML（超文本标记语言）：验证清洗、禁网、本地图片引用、超大 DOM（文档对象模型）拒绝和恶意实体。

### 检查点 Q4：旧 DOC（Word 二进制文档）外部适配器

定义与设备端相同的导入回包协议：输入文件哈希、转换产物、警告与转换器版本。优先实现 Windows（视窗系统）或服务器 LibreOffice（自由办公套件）无界面转换；离线设备只显示可执行的另存说明。不要让 AI（人工智能）直接调用任意 LibreOffice（自由办公套件）命令。

### 检查点 Q5：移除重型默认依赖

只有当新的 DOCX（Word 开放 XML 文档）引擎通过现有夹具、长文压力、损坏文件与来源锚点测试后，才移除默认 POI（Office 文档库）。迁移期可以在调试或测试构建中双跑并比较输出，但正式使用时只跑一个引擎，避免双倍成本。

## 八、最终建议

对 Novex 当前目标，最小而可持续的组合是：

- DOCX（Word 开放 XML 文档）：长期使用平台 ZIP（压缩归档）与拉取式 XML（可扩展标记语言）解析；短期可用 Java Mammoth（Java 版语义转换器）快速打通语义链路；
- DOC（旧版 Word 二进制文档）：外部 LibreOffice（自由办公套件）适配器，不进基础安装包；
- PDF（便携式文档格式）：系统 `PdfRenderer（PDF 渲染器）` / `PdfRendererPreV（旧系统兼容 PDF 渲染器）`，旧系统暂留 `pdfbox-android（安卓移植版 PDFBox）`；
- TXT（纯文本）：平台流式读取；
- Markdown（轻量标记语言）：默认流式章节索引，严格语义时按需用 `commonmark-java（CommonMark Java 解析器）`；
- HTML（超文本标记语言）：jsoup（Java HTML 解析器）清洗和解析，完全禁网；
- 所有格式统一进入 `DocumentIR（统一文档中间表示）`，统一由标准文档工具读取。

这套方案的重点不是“找到一个能吃所有文件的万能库”，而是让 AI（人工智能）获得**有界、可搜索、可定位、可审计**的文档能力。解析器以后可以替换，Novex 的工具协议、对话引用和长文工作区不需要跟着重写。

## 九、验证边界

- 官方资料没有提供这些方案在同一 Android（安卓系统）设备、同一文档集上的速度与峰值内存对比；本文对性能的排序是基于流式 / DOM（文档对象模型）/ 完整对象图架构做出的推断，最终必须用 Novex 夹具实测。
- 表中的制品大小是压缩依赖或当前本地文件大小，不等于 R8（安卓代码压缩器）后的 APK（安卓应用安装包）增量。
- 本轮在 macOS（苹果桌面系统）环境尝试复跑目标单元测试时，机器没有可用 Java（爪哇语言）运行时，因此没有生成新的测试通过证据；本文只核对了现有实现和测试源文件。产品源码与版本历史均未修改。

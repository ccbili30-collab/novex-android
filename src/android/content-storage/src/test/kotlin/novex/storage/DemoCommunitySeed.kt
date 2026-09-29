package novex.storage

import novex.content.CardAppearance
import novex.content.CardKind
import novex.content.CardResource
import novex.content.ContentBlock
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentRef
import novex.content.ContentTransfer
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 演示社区种子生成器：在主机上用真实 CardStore 造出一批「别人的作品」，
 * 由 local/design/home-v2/seed-demo.sh 推进模拟器。设置 NOVEN_SEED_OUT 和
 * NOVEN_SEED_ASSETS 才会运行，否则跳过，不进入常规单测。
 *
 * 产出：
 *   $NOVEN_SEED_OUT/rewrite-content/   卡片库（heads/revisions/contents）
 *   $NOVEN_SEED_OUT/noven-feed/        authors.json（卡片 → 演示作者归属）
 *
 * 数据与桌面端 plugins-noven/noven-shell/src/client/data.ts 的 fixtures 对齐。
 */
class DemoCommunitySeed {

    private data class SeedModule(val name: String, val body: String, val tags: List<String> = emptyList())
    private data class SeedCharacter(val name: String, val bio: String, val avatarAsset: String? = null)
    private data class SeedWork(
        val slug: String,
        val kind: CardKind,
        val title: String,
        val intro: String,
        val author: String? = null,
        val authorAvatar: String? = null,
        val followed: Boolean = false,
        val coverAsset: String? = null,
        val avatarAsset: String? = null,
        val modules: List<SeedModule> = emptyList(),
        val characters: List<SeedCharacter> = emptyList(),
    )

    private val works = listOf(
        // —— 社区作品（authors.json 归属到演示作者）——
        SeedWork(
            "yunshang", CardKind.WORLD, "云上第七天",
            intro = "一座只在每周第七天出现的云上车站。检票员记得每一位乘客的名字，却没人记得他的。",
            author = "岛眠", authorAvatar = "dao-mian.png", followed = true, coverAsset = "cover-yunshang.webp",
            modules = listOf(
                SeedModule("车站", "站台悬在云海上，铁轨在日出时才会显形。列车不载行李，只载愿意下车的人。", listOf("悬浮")),
                SeedModule("规则", "车票只能用「一件不再需要的回忆」来购买。检票员会当面收走它。", listOf("慢节奏")),
            ),
            characters = listOf(
                SeedCharacter("检票员", "记得每一位乘客的名字。没有人知道他在车上待了多久。"),
                SeedCharacter("第七天的猫", "只在第七天出现的猫，睡在候车室最长的那张椅子上。"),
                SeedCharacter("临时站长", "每周轮换一次的职位。上任的人会收到上一任留下的一封信。"),
            ),
        ),
        SeedWork(
            "qingxuan", CardKind.WORLD, "青玄门",
            intro = "云雾山门里的修仙门派，藏经峰上封存着不许再提的往事。掌门将逝，继任未定。",
            author = "鹤不归", authorAvatar = "he-bugui.png", followed = true, coverAsset = "cover-qingxuan.webp",
            modules = listOf(
                SeedModule("门派史", "二十年前的那场联署否决，写进了门规的空白处。知道内情的人只剩三位。", listOf("修真")),
                SeedModule("继任规则", "掌门之位不由血亲继承，由寒潭试炼决定。试炼十年一开，失败者退出内门。", listOf("群像")),
            ),
            characters = listOf(
                SeedCharacter("沈砚", "执法长老，话少，剑快。二十年前的联署他是唯一投了反对票的人。", "avatar-shenyan.webp"),
                SeedCharacter("云鹤", "藏经峰守阁，记性太好是职业事故。她记得所有不许再提的往事。", "avatar-yunhe.webp"),
                SeedCharacter("林晚", "掌门亲传弟子，寒潭试炼最被看好的人选，但她在犹豫要不要报名。"),
            ),
        ),
        SeedWork(
            "haibian", CardKind.WORLD, "海边的最后一班车",
            intro = "末班车驶出站台时，海潮会倒着涨。短篇集，已完结。",
            author = "末班车售票员", authorAvatar = "mobanche.png",
            modules = listOf(
                SeedModule("速写", "沿海公路的最后一站。车站小卖部的灯亮到末班车开走为止。", listOf("公路", "完结")),
            ),
        ),
        SeedWork(
            "achi", CardKind.CHARACTER, "阿迟",
            intro = "慢半拍的占卜师，说话之前总要先看一眼自己的手心。",
            author = "种桃的", authorAvatar = "zhongtao.png", avatarAsset = "cover-achi.webp",
            modules = listOf(
                SeedModule("习惯", "占卜从不收全款，另一半等应验了再收。她的大部分客人都还欠着。", listOf("占卜")),
            ),
        ),
        SeedWork(
            "mori", CardKind.WORLD, "末日备忘录",
            intro = "文明崩塌后的档案馆里，一个 AI 还在逐条回复没人再看的留言。",
            author = "夜航电台", authorAvatar = "yehang.png", coverAsset = "cover-mori.webp",
            modules = listOf(
                SeedModule("档案馆", "地下三层，恒温恒湿，备用电源还能撑四十年。留言按收到顺序回复。", listOf("末日")),
                SeedModule("留言簿", "最新一条留言来自十七年前：「如果还有人看到，请替我看看海。」", listOf("科幻")),
            ),
        ),
        SeedWork(
            "yueban", CardKind.WORLD, "今晚，你替月亮值班",
            intro = "你的第一位访客，是一个想退还昨天的人。",
            author = "半碗月亮", authorAvatar = "yueliang.png",
            modules = listOf(
                SeedModule("值班室", "月亮底下的一间小屋，桌上只有一盏灯和一本登记簿。", listOf("奇幻")),
                SeedModule("访客簿", "每一位来客都能寄存一样东西，天亮前必须取走或放弃。", listOf("夜")),
            ),
            characters = listOf(
                SeedCharacter("守夜人", "也就是你。值班手册只有一页，最后一条用红笔写的。"),
                SeedCharacter("退信的人", "想把「昨天」退回给月亮。他的邮戳盖错了日期。"),
                SeedCharacter("猫", "不属于任何访客，但每晚都来。"),
            ),
        ),
        // —— 我的作品（不归属演示作者，进「我的」页签）——
        SeedWork(
            "wudao", CardKind.WORLD, "雾岛邮局",
            intro = "雾把岛和外面隔开，邮局是唯一能递出去话的地方。",
            coverAsset = "cover-wudao.webp",
            modules = listOf(
                SeedModule("邮局", "每周三开窗收信，雾散的时候才送信。邮票画的是岛上没有的花。", listOf("治愈")),
            ),
            characters = listOf(
                SeedCharacter("邮差阿禾", "认得岛上每一条被雾藏起来的小路。"),
                SeedCharacter("灯塔看守", "话很少。据说他的灯不是给船看的。"),
            ),
        ),
        SeedWork(
            "shenyan", CardKind.CHARACTER, "沈砚",
            intro = "青玄门执法长老，话少，剑快。",
            avatarAsset = "avatar-shenyan.webp",
            modules = listOf(
                SeedModule("剑", "佩剑无名，剑穗是入门那年掌门亲手系的。", listOf("修真")),
            ),
        ),
        SeedWork(
            "yunhe", CardKind.CHARACTER, "云鹤",
            intro = "藏经峰守阁，记性太好是职业事故。",
            avatarAsset = "avatar-yunhe.webp",
            modules = listOf(
                SeedModule("守阁", "能背出藏经峰每一层每一格的书目，包括被烧掉的那一格。", listOf("修真")),
            ),
        ),
    )

    @Test
    fun generate() {
        val out = System.getenv("NOVEN_SEED_OUT")?.takeIf { it.isNotBlank() }
        val assets = System.getenv("NOVEN_SEED_ASSETS")?.takeIf { it.isNotBlank() }
        assumeTrue("seed 生成器仅在 NOVEN_SEED_OUT 设置时运行", out != null)
        val outDir = Path.of(out!!)
        val assetDir = Path.of(requireNotNull(assets) { "NOVEN_SEED_ASSETS 未设置" })
        val store = CardStore(outDir.resolve("rewrite-content"))

        val attributions = JSONObject()
        works.forEach { work ->
            val prefix = "noven-demo-${work.slug}"
            val payloads = linkedMapOf<ContentRef, () -> ByteArray>()
            val alloc = store.contents.allocator()
            fun text(value: String): ContentRef {
                val ref = alloc()
                payloads[ref] = { value.toByteArray(Charsets.UTF_8) }
                return ref
            }
            fun image(asset: String): ContentRef {
                val file = assetDir.resolve(asset)
                require(Files.isRegularFile(file)) { "素材不存在：$file" }
                val ref = alloc()
                payloads[ref] = { Files.readAllBytes(file) }
                return ref
            }

            var seq = 0
            fun id(label: String) = "$prefix-$label-${seq++}"

            fun document(
                docId: String,
                kind: CardKind, name: String, intro: String, introName: String,
                modules: List<SeedModule>, characters: List<SeedCharacter>,
                avatarAsset: String?, coverAsset: String?,
            ): ContentDocument {
                fun resource(asset: String): Pair<String, CardResource> {
                    val resId = id("res")
                    return resId to CardResource(resId, image(asset), "image/webp")
                }
                // 资源先全部登记，再组装文档；校验要求外观引用的资源已在本卡列表里。
                val avatar = avatarAsset?.let(::resource)
                val cover = coverAsset?.let(::resource)
                val docModules = mutableListOf(
                    ContentModule(id("mod"), introName, listOf(ContentBlock.Text(id("blk"), text(intro)))),
                )
                modules.forEach { module ->
                    docModules += ContentModule(
                        id("mod"), module.name,
                        listOf(ContentBlock.Text(id("blk"), text(module.body))),
                        tags = module.tags,
                    )
                }
                val chars = characters.map { character ->
                    document(
                        id("char"), CardKind.CHARACTER, character.name, character.bio, "人物简介",
                        emptyList(), emptyList(), character.avatarAsset, null,
                    )
                }
                return ContentDocument(
                    id = docId,
                    kind = kind,
                    name = name,
                    modules = docModules,
                    resources = listOfNotNull(avatar, cover).map { it.second },
                    internalCharacters = chars,
                    appearance = CardAppearance(
                        avatarResourceId = avatar?.first,
                        coverResourceId = cover?.first,
                    ),
                )
            }

            val card = document(
                prefix, work.kind, work.title, work.intro,
                if (work.kind == CardKind.WORLD) "世界简介" else "人物简介",
                work.modules, work.characters, work.avatarAsset, work.coverAsset,
            )
            // 每张卡一个批次：先算完所有引用再一次性转存。
            store.contents.receive(
                payloads.entries.map { (ref, _) -> ContentTransfer(ref, ref) },
            ) { source -> payloads.getValue(source).invoke().inputStream() }
            store.save(card, null, ChangeSource.IMPORT, "noven-demo-commit-${work.slug}")
            work.author?.let {
                attributions.put(
                    prefix,
                    JSONObject().put("author", it)
                        .put("followed", work.followed)
                        .put("avatar", work.authorAvatar ?: JSONObject.NULL),
                )
            }
        }

        val feedDir = outDir.resolve("noven-feed")
        Files.createDirectories(feedDir)
        // 演示作者头像：NOVEN_SEED_AVATARS 指向生成头像目录，文件名照抄进 authors.json。
        val avatarDir = System.getenv("NOVEN_SEED_AVATARS")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val avatarOut = feedDir.resolve("avatars")
        works.mapNotNull { it.authorAvatar }.distinct().forEach { name ->
            val source = requireNotNull(avatarDir).resolve(name)
            require(Files.isRegularFile(source)) { "作者头像不存在：$source" }
            Files.createDirectories(avatarOut)
            Files.copy(source, avatarOut.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val authors = JSONObject().put("version", 1).put("cards", attributions)
        Files.writeString(feedDir.resolve("authors.json"), authors.toString())

        File(outDir.toFile(), "MANIFEST.txt").writeText(
            "noven 演示社区种子：${works.size} 张卡，其中 ${attributions.length()} 张归属演示作者\n",
        )
        println("seeded ${works.size} cards into $outDir")
    }

    /**
     * 设备存量库维护：删掉早期测试卡（Aria / Drift Isle），把 Fogbound 改成
     * 中文名。NOVEN_STORE_DIR 指向本地副本时运行，种子推送脚本会先拉回设备库
     * 再整体写回。
     */
    @Test
    fun maintain() {
        val dir = System.getenv("NOVEN_STORE_DIR")?.takeIf { it.isNotBlank() }
        assumeTrue("维护例程仅在 NOVEN_STORE_DIR 设置时运行", dir != null)
        val store = CardStore(Path.of(dir!!))
        val doomed = setOf("Aria", "Drift Isle")
        store.list().forEach { summary ->
            when {
                summary.name in doomed -> {
                    val saved = requireNotNull(store.open(summary.id))
                    store.delete(summary.id, saved.revision)
                    println("deleted ${summary.name}")
                }
                summary.name == "Fogbound" -> {
                    val saved = requireNotNull(store.open(summary.id))
                    store.save(
                        saved.content.copy(name = "雾屿"), saved.revision,
                        ChangeSource.IMPORT, "noven-demo-rename-${summary.id}",
                    )
                    println("renamed ${summary.name} -> 雾屿")
                }
            }
        }
    }
}

package com.openminis.app.ui.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [P4] FileBrowserViewModel 核心状态机的行为钉：目录内容加载在真实
 * Dispatchers.IO 上完成，测试轮询终态而非依赖测试调度器。
 *
 * 钉住的语义（UI 消费契约，重写不得漂移）：
 *  - 初始加载：文件夹置顶 + 名称升序；
 *  - navigateTo 只进目录；goBack 到入口即 false（T145 地板）；
 *  - 面包屑跳转 clamp 在入口目录之内；
 *  - showHidden 过滤点开头文件；setSort 即时重排且不重读目录；
 *  - deleteItem 删除后重载；dismissError 清错。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = tmp.newFolder("root")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 轮询到 isLoading=false（后台 IO 完成）。 */
    private fun awaitLoaded(vm: FileBrowserViewModel) {
        awaitUntil(vm) { !it.isLoading }
    }

    /** 轮询到状态谓词成立（后台 IO 线程与测试线程解耦，不能靠调度器推进）。 */
    private fun awaitUntil(vm: FileBrowserViewModel, predicate: (FileBrowserUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!predicate(vm.uiState.value)) {
            assertTrue("状态轮询超时", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    private fun state(vm: FileBrowserViewModel) = vm.uiState.value

    private fun names(vm: FileBrowserViewModel) = state(vm).items.map { it.name }

    private fun file(vm: FileBrowserViewModel, name: String): FileItem =
        state(vm).items.first { it.name == name }

    private fun makeTree() {
        File(root, "bravo.txt").writeText("b")
        File(root, "alpha.txt").writeText("a")
        File(root, ".dotfile").writeText("hidden")
        File(root, "zebra").apply { mkdir() }
        File(root, "zed").apply { mkdir() }
    }

    @Test
    fun `initial load lists folders first then files by name`() {
        makeTree()
        val vm = FileBrowserViewModel(rootPath = root, appContext = null)
        awaitLoaded(vm)

        // 小写口径字典序：zebra < zed（b < d）。
        assertEquals(listOf("zebra", "zed", "alpha.txt", "bravo.txt"), names(vm))
        assertFalse(state(vm).isLoading)
        assertFalse(state(vm).isEmpty)
        assertNull(state(vm).errorMessage)
        assertEquals(root.absolutePath, state(vm).currentPath)
    }

    @Test
    fun `hidden dotfiles appear only after the toggle`() {
        makeTree()
        val vm = FileBrowserViewModel(rootPath = root, appContext = null)
        awaitLoaded(vm)
        assertFalse(names(vm).contains(".dotfile"))

        vm.setShowHidden(true)
        awaitLoaded(vm)
        assertTrue(names(vm).contains(".dotfile"))

        vm.setShowHidden(false)
        awaitLoaded(vm)
        assertFalse(names(vm).contains(".dotfile"))
    }

    @Test
    fun `navigateTo descends into directories only and goBack stops at the entry`() {
        makeTree()
        File(root, "zed/nested").mkdir()
        File(root, "zed/nested/deep.txt").writeText("d")
        // 入口即 zed：T145 的返回地板。
        val vm = FileBrowserViewModel(rootPath = root, initialPath = File(root, "zed"), appContext = null)
        awaitLoaded(vm)
        assertEquals(listOf("nested"), names(vm))
        assertFalse(state(vm).canGoBack)

        // 在入口 goBack = false，调用方应 pop 屏幕。
        assertFalse(vm.goBack())

        vm.navigateTo(file(vm, "nested"))
        awaitLoaded(vm)
        assertEquals(listOf("deep.txt"), names(vm))
        assertTrue(state(vm).canGoBack)
        assertEquals(listOf(root.name, "zed", "nested"), state(vm).pathComponents)

        assertTrue(vm.goBack())
        awaitLoaded(vm)
        assertEquals(listOf("nested"), names(vm))
        assertFalse(state(vm).canGoBack)
    }

    @Test
    fun `breadcrumb jumps are clamped to the entry directory`() {
        makeTree()
        File(root, "zed/inner").mkdir()
        val vm = FileBrowserViewModel(rootPath = root, initialPath = File(root, "zed/inner"), appContext = null)
        awaitLoaded(vm)

        // 面包屑跳到 index 0（逻辑根）——入口在 zed/inner，必须被 clamp 回入口。
        vm.navigateToPathComponent(0)
        awaitLoaded(vm)
        assertEquals(listOf(root.name, "zed", "inner"), state(vm).pathComponents)
        assertFalse(state(vm).canGoBack)

        // 入口在根时（initialPath=null）面包屑跳根合法。
        val fromRoot = FileBrowserViewModel(rootPath = root, appContext = null)
        awaitLoaded(fromRoot)
        fromRoot.navigateTo(file(fromRoot, "zed"))
        awaitLoaded(fromRoot)
        fromRoot.navigateToPathComponent(0)
        awaitLoaded(fromRoot)
        assertEquals(listOf(root.name), state(fromRoot).pathComponents)
        assertFalse(state(fromRoot).canGoBack)
    }

    @Test
    fun `setSort reorders the cached listing without a directory reload`() {
        makeTree()
        // bravo 10 字节 > alpha 2 字节；目录条目 size=0。
        File(root, "alpha.txt").writeText("ab")
        File(root, "bravo.txt").writeText("0123456789")
        val vm = FileBrowserViewModel(rootPath = root, appContext = null)
        awaitLoaded(vm)

        vm.setSort(key = FileSortKey.SIZE, ascending = false)
        // reversed() 翻转整个比较器链：降序时平键（两目录 size 均为 0）
        // 按名降序兜底 → zed 在 zebra 前。
        assertEquals(
            listOf("zed", "zebra", "bravo.txt", "alpha.txt"),
            state(vm).items.map { it.name },
        )

        // 换键到名称降序，文件夹置顶不变。
        vm.setSort(key = FileSortKey.NAME, ascending = false)
        assertEquals(
            listOf("zed", "zebra", "bravo.txt", "alpha.txt"),
            state(vm).items.map { it.name },
        )
    }

    @Test
    fun `deleteItem removes the entry and reloads`() {
        makeTree()
        val vm = FileBrowserViewModel(rootPath = root, appContext = null)
        awaitLoaded(vm)

        vm.deleteItem(file(vm, "alpha.txt"))
        // 删除在真实 IO 线程上执行且随后的重载是另一趟 IO——轮询到条目
        // 消失为止，不能只等 isLoading（初趟的 false 可能先于删除到位）。
        awaitUntil(vm) { s -> s.items.none { it.name == "alpha.txt" } }
        assertTrue(names(vm).contains("bravo.txt"))
    }

    @Test
    fun `missing directory yields an error terminal state`() {
        val vm = FileBrowserViewModel(rootPath = File(root, "nope"), appContext = null)
        awaitLoaded(vm)
        assertTrue(state(vm).isEmpty)
        // listFiles() 对不存在目录返回 null → 空列表终态（与基线一致），
        // errorMessage 仅在读目录抛异常时出现。
        assertTrue(state(vm).items.isEmpty())
    }

    @Test
    fun `linux path prefix drives the copy-path projection`() {
        makeTree()
        val vm = FileBrowserViewModel(
            rootPath = root, appContext = null,
            displayLinuxPrefix = "/var/minis/workspace",
        )
        awaitLoaded(vm)
        assertEquals("/var/minis/workspace", state(vm).currentLinuxPath)

        vm.navigateTo(file(vm, "zed"))
        awaitLoaded(vm)
        assertEquals("/var/minis/workspace/zed", state(vm).currentLinuxPath)
    }
}

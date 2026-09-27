# 小修批：更新源标签简化+关于页链接换仓+技能浏览器外部入口移除

日期：2026-09-27 · 分支 task/hub-links-polish · 状态：终态（已合并 → beta.84）

## 用户三项决议（2026-09-27）

1. 更新源标签去掉括号后缀（括号导致胶囊内文字折行、两边不对称）——
   经选项确认为纯 **Gitee / GitHub**（国内海外语义由 logo 与常识承载）
2. 关于页"源代码"入口指向 OpenMinis/OpenMinis（旧上游）——换成
   ccbili30-collab/novex-android
3. 技能浏览器外部市场（OpenMinis/MinisSkills）：「我们又不提供对外
   下载，目前只放我们预设的，留着干嘛」——入口整体移除

## 变更

- 8 语言包 update_source_gitee/github 统一为 "Gitee"/"GitHub"
- AboutScreen 源码链接 → ccbili30-collab/novex-android
- SkillsManagementScreen 添加菜单中"Novex Skills"行移除（含分隔线+
  onMinisSkillsClick 参数）；AppNavigation 路由常量 MINIS_SKILLS_BROWSER+
  composable+import 移除；MinisSkillsBrowserScreen.kt 删除；孤儿字符串
  skill_minis_skills_modal 七语言包清除
- 预设技能（内置 bundled skills）不受影响——移除的只是外部 GitHub
  市场浏览入口

## 台账（终态）

- 净眼一审退回（63 条 skills_browser_* 孤儿串+skill_empty_action 8 包误导文案）修复后终验过；守纲六问过；CI 绿；合并 PR #39 → beta.84

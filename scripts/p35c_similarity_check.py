#!/usr/bin/env python3
"""P3.5c 真重写自查：新实现文件 vs 上游基线同路径旧文件的混合相似度。

upstream_audit.py 只比「同路径」文件——实现搬进 novex.android.* 后，
新路径无基线可比、旧路径只剩门面，两者的表都会失真。本脚本把口径
（剥注释+空白归一+大小写折叠的行级 SequenceMatcher，autojunk 关闭）
原样复用，改为「新实现 ↔ 该件的上游基线旧路径」配对，度量大头是否
真的重写而非直译换皮。

多对一（一个旧件拆成多个新件）时取各新件对同一基线的比值并列展示，
门槛仍为 <40%。

用法（仓库根）：python3 scripts/p35c_similarity_check.py
"""

import subprocess
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from upstream_audit import normalized_lines, line_similarity  # noqa: E402

BASE = "82c2eb0e3790104c114be755d77dba232518d6e0"
APP = "src/android/app/src/main/java"

# 新实现文件 → 上游基线里的对应旧路径（可多对一/一对多）
PAIRS = {
    # 公共新件：对照被合并/拆分的基线源（净眼复核补收）
    "novex/android/authkit/RefreshGate.kt": "com/openminis/app/auth/GeminiOAuthManager.kt",  # 对照被合并的刷新拷贝之一
    # auth → authkit
    "novex/android/authkit/OAuthWire.kt": "com/openminis/app/auth/OAuthManager.kt",
    "novex/android/authkit/PkceMaterial.kt": "com/openminis/app/auth/OAuthManager.kt",
    "novex/android/authkit/LoopbackReceiver.kt": "com/openminis/app/auth/OAuthCallbackServer.kt",
    "novex/android/authkit/LoopbackRedirectRelay.kt": "com/openminis/app/auth/OAuthRedirectActivity.kt",
    "novex/android/authkit/CredentialVault.kt": "com/openminis/app/auth/OAuthManager.kt",
    "novex/android/authkit/VendorLoginFlow.kt": "com/openminis/app/auth/OAuthManager.kt",
    "novex/android/authkit/ClaudeLoginFlow.kt": "com/openminis/app/auth/ClaudeOAuthManager.kt",
    "novex/android/authkit/CodexLoginFlow.kt": "com/openminis/app/auth/OpenAIOAuthManager.kt",
    "novex/android/authkit/GeminiLoginFlow.kt": "com/openminis/app/auth/GeminiOAuthManager.kt",
    "novex/android/authkit/XaiLoginFlow.kt": "com/openminis/app/auth/XAIOAuthManager.kt",
    "novex/android/authkit/KimiLoginFlow.kt": "com/openminis/app/auth/KimiOAuthManager.kt",
    # soul
    "novex/android/soul/SoulDocument.kt": "com/openminis/app/agent/SoulStore.kt",
    "novex/android/soul/SoulRepository.kt": "com/openminis/app/agent/SoulStore.kt",
    # models
    "novex/android/models/ModelsDevCatalog.kt": "com/openminis/app/provider/ModelsDevApi.kt",
    # deeplink
    "novex/android/navlink/MinisLinks.kt": "com/openminis/app/deeplink/DeepLinkHandler.kt",
    "novex/android/navlink/PendingLinkFx.kt": "com/openminis/app/deeplink/DeepLinkCoordinator.kt",
    # network / i18n / power / util
    "novex/android/netwatch/LinkMonitor.kt": "com/openminis/app/network/NetworkMonitor.kt",
    "novex/android/localekit/LocaleOverride.kt": "com/openminis/app/i18n/LocaleWrap.kt",
    "novex/android/powerguard/OemPowerGates.kt": "com/openminis/app/power/PowerOptimizationManager.kt",
    "novex/android/vault/SelfHealingPrefs.kt": "com/openminis/app/util/EncryptedPrefsFactory.kt",
    # share
    "novex/android/sharekit/ShareWire.kt": "com/openminis/app/share/PendingShare.kt",
    "novex/android/sharekit/ShareInbox.kt": "com/openminis/app/share/SharedShareStore.kt",
    "novex/android/sharekit/ShareBuffer.kt": "com/openminis/app/share/ShareCoordinator.kt",
    "novex/android/sharekit/ChatZipExporter.kt": "com/openminis/app/share/ChatExporter.kt",
    "novex/android/sharekit/ShareHandoffLadder.kt": "com/openminis/app/share/ShareHandoffPolicy.kt",
    "novex/android/sharekit/InboundShareIntake.kt": "com/openminis/app/share/ShareReceiverActivity.kt",
}

# 旧路径门面/正典（钉形件）→ 基线：度量剩余形态相似度
OLD_PATH_KEEPERS = {
    "com/openminis/app/auth/KimiDeviceFlow.kt": "上游未动件，就地真重写",
    "com/openminis/app/auth/OAuthRedirectActivity.kt": "Manifest 壳",
    "com/openminis/app/agent/SoulStore.kt": "门面+钉形 verdict",
    "com/openminis/app/deeplink/DeepLinkHandler.kt": "门面+钉形 sealed",
    "com/openminis/app/deeplink/DeepLinkCoordinator.kt": "门面+钉形嵌套",
    "com/openminis/app/power/PowerOptimizationManager.kt": "门面+钉形枚举",
    "com/openminis/app/share/PendingShare.kt": "钉形 DTO 壳",
    "com/openminis/app/share/ShareReceiverActivity.kt": "Manifest 壳",
    "com/openminis/app/share/ShareHandoffPolicy.kt": "门面+钉形枚举",
    "com/openminis/app/auth/OAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/ClaudeOAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/GeminiOAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/XAIOAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/KimiOAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/OpenAIOAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/OpenRouterOAuthManager.kt": "typealias 门面",
    "com/openminis/app/auth/OAuthCallbackServer.kt": "typealias 门面",
    "com/openminis/app/provider/ModelsDevApi.kt": "typealias 门面",
    "com/openminis/app/network/NetworkMonitor.kt": "typealias 门面",
    "com/openminis/app/i18n/LocaleWrap.kt": "typealias 门面",
    "com/openminis/app/util/EncryptedPrefsFactory.kt": "typealias 门面",
    "com/openminis/app/share/ChatExporter.kt": "typealias 门面",
    "com/openminis/app/share/ShareCoordinator.kt": "typealias 门面",
    "com/openminis/app/share/SharedShareStore.kt": "typealias 门面",
}


def blob(path):
    out = subprocess.run(["git", "show", f"{BASE}:{path}"], capture_output=True)
    if out.returncode != 0:
        return None
    return out.stdout.decode("utf-8", errors="replace")


def main():
    rows = []
    for new, old in sorted(PAIRS.items()):
        base_src = blob(f"{APP}/{old}")
        if base_src is None:
            rows.append((new, old, None, 0))
            continue
        cur_src = open(f"{APP}/{new}", encoding="utf-8").read()
        sim = line_similarity(normalized_lines(cur_src), normalized_lines(base_src))
        rows.append((new, old, round(sim * 100, 1), len(normalized_lines(cur_src))))

    print("== 新实现 vs 上游基线（门槛 <40%）==")
    worst = 0.0
    for new, old, sim, n in rows:
        flag = "" if sim is not None and sim < 40 else "  <<<< 超标"
        if sim is not None:
            worst = max(worst, sim)
        pct = "-" if sim is None else f"{sim}%"
        print(f"  {pct:>7}  {n:>4}行  {new.split('/')[-1]:<24} ↔ {old.split('/')[-1]}{flag}")
    print(f"  最差 {worst}%")

    print()
    print("== 旧路径门面/钉形件 vs 基线（upstream_audit 同口径）==")
    for old, note in sorted(OLD_PATH_KEEPERS.items()):
        base_src = blob(f"{APP}/{old}")
        if base_src is None:
            print(f"  {'-':>7}  {old.split('/')[-1]:<32} {note}")
            continue
        cur_src = open(f"{APP}/{old}", encoding="utf-8").read()
        sim = line_similarity(normalized_lines(cur_src), normalized_lines(base_src))
        flag = "" if sim * 100 < 40 else "  <<<< 超标（冻结面记档）"
        print(f"  {sim*100:>6.1f}%  {old.split('/')[-1]:<32} {note}{flag}")


if __name__ == "__main__":
    main()

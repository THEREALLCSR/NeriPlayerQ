"""QQ 音乐能力真值表的一致性测试。

背景（`02` 三件事之三、`13` 2.5、`17` Q3）：
`README.qqmusic.md` 第 0 节那张能力表是手工维护的。2026-10-31 曾把 Cookie 过期
误判成"功能失效"，教训是"代码状态与文档状态会悄悄分叉"。本测试把能力表变成
可机读、可校验的真值源：

1. README 表与机读清单的能力项/状态一致（文档不能自己改了不算）；
2. 每个"可用"能力都有真实代码入口（防止声称能用但代码没了）；
3. README 引用的证据文件必须存在（当前 probe2.py / VERIFIED_CAPABILITIES.md 已断链）。

CI 无 QQ Cookie，无法真联网冒烟；这里校验的是"能力声明的一致性"，
联网冒烟是另一个形态（debug 页手动 probe），两者互补。
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
README = ROOT / "README.qqmusic.md"

# 真值源模块（本轮要创建）——先写测试引用它，当前应因缺失而红
from qq_capabilities import CAPABILITIES, EVIDENCE_FILES  # noqa: E402


def parse_readme_capabilities() -> dict:
    """解析 README 第 0 节能力表，返回 {能力名: 状态符号}。"""
    text = README.read_text(encoding="utf-8")
    section = text.split("## 0.", 1)[1].split("## 1.", 1)[0]
    result = {}
    for line in section.splitlines():
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or cells[0] in ("能力", "---") or set(cells[0]) <= {"-"}:
            continue
        name = cells[0].replace("**", "")
        status = cells[1]
        symbol = "✅" if "✅" in status else "❌" if "❌" in status else "⚠️" if "⚠" in status else "?"
        result[name] = symbol
    return result


class QqCapabilityTruthTest(unittest.TestCase):
    def test_readme_and_truth_source_have_same_capabilities(self):
        readme = parse_readme_capabilities()
        self.assertTrue(readme, "没能从 README 第 0 节解析出能力表")
        self.assertEqual(
            set(readme),
            set(CAPABILITIES),
            "README 能力表与机读真值源的能力项不一致：\n"
            f"  只在 README: {sorted(set(readme) - set(CAPABILITIES))}\n"
            f"  只在真值源: {sorted(set(CAPABILITIES) - set(readme))}",
        )

    def test_status_matches_readme(self):
        readme = parse_readme_capabilities()
        for name, symbol in readme.items():
            if name not in CAPABILITIES:
                continue
            self.assertEqual(
                symbol,
                CAPABILITIES[name].status,
                f"能力「{name}」状态不一致：README={symbol}, 真值源={CAPABILITIES[name].status}",
            )

    def test_available_capabilities_have_code_entry(self):
        for name, cap in CAPABILITIES.items():
            if cap.status != "✅":
                continue
            for rel in cap.code_entries:
                path = ROOT / rel
                self.assertTrue(
                    path.exists(),
                    f"能力「{name}」声称可用，但代码入口不存在: {rel}",
                )

    def test_readme_evidence_files_exist(self):
        text = README.read_text(encoding="utf-8")
        for rel in EVIDENCE_FILES:
            path = ROOT / rel
            self.assertTrue(
                path.exists(),
                f"README 引用的证据文件不存在: {rel}（能力表声称有证据，实际断链）",
            )
        # 反向：README 里写到的 tools/... 路径都应在白名单内
        referenced = set(re.findall(r"`(tools/[^`]+)`", text))
        self.assertEqual(
            referenced,
            set(EVIDENCE_FILES),
            "README 引用的证据文件与真值源清单不一致（有新增未被登记）",
        )


if __name__ == "__main__":
    unittest.main()

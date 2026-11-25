"""四音源能力矩阵自检。

守住矩阵本身的完整性：音源齐全、状态符号合法、✅ 能力必须有代码入口、
可对齐项必须有参照源、gaps() 计算正确。CI 通过 unittest discover 自动收录。
"""

import pathlib
import unittest

from source_capability_matrix import (
    MATRIX,
    SOURCES,
    Cell,
    cells_for,
    gaps,
)


REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
VALID_STATUS = {"✅", "⚠️", "❌", "—"}


class SourceCapabilityMatrixTest(unittest.TestCase):
    def test_matrix_has_all_sources_for_every_capability(self):
        self.assertGreater(len(MATRIX), 0)
        for cap in MATRIX:
            for source in SOURCES:
                self.assertIn(source, cap.cells, f"{cap.name} 缺少 {source} 的状态")

    def test_status_symbols_are_legal(self):
        for cap in MATRIX:
            for source, cell in cap.cells.items():
                self.assertIn(cell.status, VALID_STATUS, f"{cap.name}/{source} 非法状态 {cell.status}")

    def test_available_capabilities_have_code_entry(self):
        for cap in MATRIX:
            for source, cell in cap.cells.items():
                if cell.status == "✅":
                    self.assertTrue(
                        cell.entry.strip(),
                        f"{cap.name}/{source} 标记 ✅ 但无代码入口",
                    )

    def test_alignable_items_have_reference_source(self):
        for cap in MATRIX:
            if cap.alignable:
                self.assertIn(cap.align_target, SOURCES, f"{cap.name} 可对齐但参照源非法")

    def test_alignable_items_explain_why(self):
        for cap in MATRIX:
            if cap.alignable:
                self.assertTrue(cap.note, f"{cap.name} 可对齐但缺依据说明")

    def test_cell_entries_that_are_paths_exist(self):
        """entry 若看起来是仓库相对路径（含 / 且以已知目录开头），必须真实存在。"""
        prefixes = ("modules/", "app/", "tools/", "docs/")
        for cap in MATRIX:
            for source, cell in cap.cells.items():
                entry = cell.entry.split()[0] if cell.entry else ""
                if entry.startswith(prefixes):
                    self.assertTrue(
                        (REPO_ROOT / entry).exists(),
                        f"{cap.name}/{source} 代码入口不存在: {entry}",
                    )

    def test_gaps_direction_is_correct(self):
        qq_lacks = gaps("qqmusic", "netease")
        self.assertTrue(any(c.name == "自动续签" for c in qq_lacks) is False)
        # 自动续签是 QQ 有、网易云无，不应出现在 "QQ 相对网易云缺失" 里
        netease_lacks = gaps("netease", "qqmusic")
        self.assertTrue(any(c.name == "自动续签" for c in netease_lacks))
        # 评论发表 QQ 是 ❌ 且不可对齐，不应出现在可对齐缺口里
        self.assertFalse(any(c.name == "评论发表/回复" for c in gaps("qqmusic", "netease")))

    def test_cells_for_all_sources(self):
        for source in SOURCES:
            rows = cells_for(source)
            self.assertEqual(len(rows), len(MATRIX))
            self.assertTrue(all(isinstance(cell, Cell) for _, _, cell in rows))


if __name__ == "__main__":
    unittest.main()

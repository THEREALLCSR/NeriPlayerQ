"""验证 CI 的 gradle-tests job 覆盖了每个应用模块的 JVM 单元测试。

背景（台账 B8/C7）：CI 通过 `:xxx:verifyCrap` 间接跑 10 个库模块的 testDebugUnitTest，
但 `:app` 从未被覆盖——它有数百个测试文件，任何一个测试源集编译失败都不会被发现。
B1（播放核心 1723 条测试因编译失败而从未执行）就是这么埋了两轮。

本测试是这条防线的机械验证：改 CI 配置让它转绿，而不是靠人记得。
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "android_ci.yml"


def gradle_tests_job_block(text: str) -> str:
    """截出 `gradle-tests:` 这一 job 的 YAML 块（到下一个顶层 job 为止）。"""
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if re.match(r"^  gradle-tests:\s*$", line):
            start = i
            break
    if start is None:
        return ""
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.match(r"^\S", lines[i]) and lines[i].strip():  # 顶层键
            end = i
            break
    return "\n".join(lines[start:end])


class UnitTestCoverageTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text(encoding="utf-8")
        self.job = gradle_tests_job_block(self.workflow)

    def test_gradle_tests_job_exists(self):
        self.assertTrue(self.job, "找不到 gradle-tests job")

    def test_app_unit_tests_are_covered(self):
        # verifyCrap 间接依赖 testDebugUnitTest，所以两种写法都算覆盖；
        # 必须带模块路径，裸 `test` 不算（那会跑全工程，语义不同且不可控）。
        covered = re.search(
            r":app:(?:testDebugUnitTest|verifyCrap|check)\b",
            self.job,
        )
        self.assertIsNotNone(
            covered,
            "gradle-tests job 没有覆盖 :app 的 JVM 单元测试。\n"
            "请在 'Run unit tests, CRAP gate and lint' 的命令里加上 "
            ":app:testDebugUnitTest（或 :app:verifyCrap）。\n"
            "当前命令:\n" + self.job,
        )

    def test_library_modules_keep_verify_crap(self):
        # 防止修 :app 时误删既有库的覆盖任务——那会让防线倒退。
        for module in ("playback:runtime", "platform", "download:runtime", "local"):
            self.assertIn(
                f":{module}:verifyCrap" if module != "local" else ":local:verifySyncIntegrationCrap",
                self.job,
                f"gradle-tests job 丢了 {module} 的覆盖任务",
            )


if __name__ == "__main__":
    unittest.main()

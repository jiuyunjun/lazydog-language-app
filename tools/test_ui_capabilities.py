import json
from pathlib import Path
import tempfile
import unittest

from check_ui_capabilities import SOURCE, inspect, text_uses


class CapabilityGuardTest(unittest.TestCase):
    def check(self, nav="", screen="", exceptions=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / SOURCE / "feature/demo").mkdir(parents=True)
            (root / "tools").mkdir()
            (root / SOURCE / "DemoNav.kt").write_text(nav, encoding="utf-8")
            (root / SOURCE / "feature/demo/DemoScreen.kt").write_text(screen, encoding="utf-8")
            (root / "tools/ui_text_exceptions.json").write_text(
                json.dumps(exceptions or []), encoding="utf-8")
            return inspect(root)

    def test_new_route_cannot_bypass_shell(self):
        problems = self.check(nav='import androidx.navigation.compose.composable as page\npage("new") {}')
        self.assertTrue(any("appScreen" in p for p in problems))

    def test_missing_policy_fails(self):
        self.assertTrue(self.check(nav='appScreen("new") { DemoScreen() }'))

    def test_disabled_reason_cannot_be_blank_or_hide_behind_an_unchecked_variable(self):
        self.assertTrue(self.check(nav='appScreen("new", ask=AskPagePolicy.Disabled(" ")) { DemoScreen() }'))
        self.assertTrue(self.check(nav='appScreen("new", ask=policy) { DemoScreen() }'))
        self.assertEqual([], self.check(nav='appScreen("new", ask=AskPagePolicy.Disabled("设置页")) { DemoScreen() }'))

    def test_host_without_provider_fails(self):
        problems = self.check(
            nav='appScreen("new", ask = AskPagePolicy.Required) { DemoScreen() }',
            screen='fun DemoScreen() { Text("加载中") }')
        self.assertTrue(any("ProvideAskContext" in p for p in problems))

    def test_missing_fallback_action_fails(self):
        problems = self.check(
            nav='appScreen("new", ask = AskPagePolicy.Required) { DemoScreen() }',
            screen='fun DemoScreen() { ProvideAskContext(null, "加载中") }')
        self.assertTrue(any("AskTopBarAction" in p for p in problems))

    def test_explicit_loading_provider_is_valid(self):
        self.assertEqual([], self.check(
            nav='appScreen("new", ask = AskPagePolicy.Required) { DemoScreen() }',
            screen='fun DemoScreen() { ProvideAskContext(null, "加载中"); AskTopBarAction() }'))

    def test_new_english_body_cannot_use_plain_text(self):
        self.assertTrue(self.check(screen='fun DemoScreen() { Text("Hello world") }'))
        self.assertTrue(self.check(screen='fun DemoScreen() { Text(text = sentence) }'))
        self.assertEqual([], self.check(screen='fun DemoScreen() { InteractiveEnglishText(sentence) }'))

    def test_alias_and_basic_text_are_not_loopholes(self):
        self.assertTrue(self.check(screen='import androidx.compose.material3.Text as Label\nfun DemoScreen() { Label(sentence) }'))
        self.assertTrue(self.check(screen='fun DemoScreen() { BasicText(sentence) }'))

    def test_exception_is_scoped_and_counted(self):
        exceptions = [{"file": (SOURCE / "feature/demo/DemoScreen.kt").as_posix(),
                       "text": "title", "count": 1, "reason": "页面标题"}]
        self.assertEqual([], self.check(screen='fun DemoScreen() { Text(title) }', exceptions=exceptions))
        self.assertTrue(self.check(screen='fun DemoScreen() { Text(title); Text(title) }', exceptions=exceptions))
        self.assertTrue(self.check(screen='fun DemoScreen() { Text(sentence) }', exceptions=exceptions))
        self.assertTrue(self.check(screen='fun DemoScreen() {}', exceptions=exceptions))

    def test_comments_and_interpolated_strings_keep_balanced_offsets(self):
        source = '''// Text(ignored)
/* Text(ignored) */
Text("${if (ok) "word" else "sentence"}")
Text(text = source.joinToString(",") { it.label })'''
        expressions = [expr for _, expr in text_uses(source)]
        self.assertEqual(2, len(expressions))
        self.assertIn('if (ok)', expressions[0])
        self.assertIn('joinToString', expressions[1])


if __name__ == "__main__":
    unittest.main()

# -*- coding: utf-8 -*-
"""tools/build_dictionary.py 的切分、词性、音标、变形规则。

运行：python -m unittest discover -s tools -p test_build_dictionary.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build_dictionary as b  # noqa: E402


class SplitSensesTest(unittest.TestCase):
    def test_splits_on_top_level_separators(self):
        self.assertEqual(b.split_senses("记录, 履历；档案; 唱片"), ["记录", "履历", "档案", "唱片"])

    def test_keeps_separators_inside_brackets(self):
        self.assertEqual(b.split_senses("覆盖；使(马,鹰等)戴头罩；给…加罩"),
                         ["覆盖", "使(马,鹰等)戴头罩", "给…加罩"])


class SensesOfTest(unittest.TestCase):
    RECORD = ("n. 记录, 履历, 唱片\\nvt. 记录, 记载\\nvi. 记录, 录音\\na. 创纪录的"
              "\\n[计] 录制, 记录")

    def test_groups_by_pos_and_merges_verb_lines(self):
        self.assertEqual(b.senses_of(self.RECORD, False), [
            ("NOUN", ["记录", "履历", "唱片"]),
            ("VERB", ["记录", "记载", "录音"]),
            ("ADJ", ["创纪录的"]),
        ])

    def test_drops_note_lines_for_single_words(self):
        self.assertEqual(b.senses_of("n. 锯子\\nsee的过去式", False), [("NOUN", ["锯子"])])

    def test_untagged_phrase_lines_become_phrase(self):
        self.assertEqual(b.senses_of("拿掉, 起飞\\n[经] 取消", True), [("PHRASE", ["拿掉", "起飞"])])

    def test_drops_person_names_and_english_glosses(self):
        self.assertEqual(b.senses_of("n. 亚伦（男子名）", False), [])
        self.assertEqual(b.senses_of("n. 比尔（男子名）, 账单, A bill.", False), [("NOUN", ["账单"])])
        self.assertEqual(b.senses_of("n. 阿德莱德（澳大利亚港市）", False), [("NOUN", ["阿德莱德（澳大利亚港市）"])])

    def test_compound_prefix(self):
        self.assertEqual(b.parse_line("vt.& vi. 开始"), ("VERB", "开始"))
        self.assertEqual(b.parse_line("abbr. 美国"), (None, "abbr. 美国"))


class IpaTest(unittest.TestCase):
    def test_single_syllable_has_no_stress_mark(self):
        self.assertEqual(b.arpabet_to_ipa(["B", "AE1", "NG", "K"]), "/bæŋk/")

    def test_stress_goes_before_onset(self):
        self.assertEqual(b.arpabet_to_ipa(["R", "EH1", "K", "ER0", "D"]), "/ˈrekərd/")
        self.assertEqual(b.arpabet_to_ipa(["AH0", "B", "AE1", "N", "D", "AH0", "N"]), "/əˈbændən/")

    def test_legal_cluster_onset(self):
        # con-STRUCT：str 是合法首音，重音符放在 s 前面。
        self.assertEqual(b.arpabet_to_ipa(["K", "AH0", "N", "S", "T", "R", "AH1", "K", "T"]), "/kənˈstrʌkt/")

    def test_secondary_stress(self):
        self.assertEqual(
            b.arpabet_to_ipa(["IH2", "N", "F", "ER0", "M", "EY1", "SH", "AH0", "N"]),
            "/ˌɪnfərˈmeɪʃən/")


class FormsTest(unittest.TestCase):
    def test_irregular_flag(self):
        forms = {(f, k): irr for f, k, irr in b.parse_exchange(
            "go", "i:going/p:went/d:gone/3:goes")}
        self.assertEqual(forms, {("going", "i"): False, ("went", "p"): True,
                                 ("gone", "d"): True, ("goes", "3"): False})

    def test_regular_spelling_changes_are_not_irregular(self):
        for lemma, exchange in [("stop", "p:stopped/i:stopping"), ("study", "p:studied/3:studies"),
                                ("make", "i:making"), ("big", "r:bigger/t:biggest"),
                                ("lie", "i:lying"), ("watch", "3:watches")]:
            for form, kind, irregular in b.parse_exchange(lemma, exchange):
                self.assertFalse(irregular, (lemma, form))

    def test_lemma_entries_are_skipped(self):
        self.assertEqual(b.parse_exchange("went", "0:go/1:p"), [])


class KeepEntryTest(unittest.TestCase):
    def row(self, word, tag="", oxford="", collins="", frq="0", bnc="0"):
        return {"word": word, "tag": tag, "oxford": oxford, "collins": collins, "frq": frq, "bnc": bnc}

    def test_rules(self):
        self.assertTrue(b.keep_entry(self.row("abandon", tag="cet4"), 100))
        self.assertTrue(b.keep_entry(self.row("ripple", frq="90"), 100))
        self.assertFalse(b.keep_entry(self.row("ripple", frq="900"), 100))
        self.assertFalse(b.keep_entry(self.row("take off", collins="3"), 100))
        self.assertTrue(b.keep_entry(self.row("take off", tag="cet4"), 100))
        self.assertFalse(b.keep_entry(self.row("Smith", frq="50"), 100))
        self.assertFalse(b.keep_entry(self.row("x2"), 100))


if __name__ == "__main__":
    unittest.main()

package com.lazydog.english.core.model

/**
 * 第一版的占位内容，全部本地写死，来自设计稿的示例数据。
 * M1 接入 Room 知识库、M3 接入 AI 生成后逐步替换。
 */
object SampleData {

    val todayTasks = listOf(
        TodayTaskPreview(TaskKind.Review, "复习到期的", "14 个词 · 2 个语法点", "4 分"),
        TodayTaskPreview(TaskKind.NewWords, "新词", "5 个", "3 分"),
        TodayTaskPreview(TaskKind.Grammar, "新语法", "现在完成进行时", "2 分"),
        TodayTaskPreview(TaskKind.Reading, "定制短文", "科技 · 约 180 词", "3 分"),
        TodayTaskPreview(TaskKind.Speaking, "朗读 3 句", "从短文里挑的", "2 分"),
        TodayTaskPreview(TaskKind.Quiz, "小测 6 题", "今天出现过的内容", "2 分"),
    )

    val newWords = listOf(
        WordCard(
            word = "linger",
            ipa = "/ˈlɪŋɡər/",
            meaningZh = "v. 逗留、迟迟不走；（气味、感觉）残留",
            exampleEn = "The smell of coffee lingered in the kitchen all morning.",
            exampleZh = "咖啡味整个早上都留在厨房里。这里强调气味没散，不是有人赖着不走。",
            encounterNote = "第 2 次见到它",
        ),
        WordCard(
            word = "draft",
            ipa = "/drɑːft/",
            meaningZh = "n. 草稿、初稿；v. 起草",
            exampleEn = "She sent me the first draft of the report before lunch.",
            exampleZh = "她午饭前把报告的初稿发给了我。",
            encounterNote = "第 1 次见到它",
        ),
        WordCard(
            word = "plausible",
            ipa = "/ˈplɔːzəbl/",
            meaningZh = "adj. 看似合理的、说得通的",
            exampleEn = "His excuse sounded plausible, but nobody really believed it.",
            exampleZh = "他的借口听起来说得通，但没人真信。",
            encounterNote = "第 1 次见到它",
        ),
        WordCard(
            word = "reluctant",
            ipa = "/rɪˈlʌktənt/",
            meaningZh = "adj. 不情愿的、勉强的",
            exampleEn = "Management was reluctant to close the floor.",
            exampleZh = "管理层不太情愿关掉这一层。",
            encounterNote = "第 3 次见到它",
        ),
        WordCard(
            word = "curb",
            ipa = "/kɜːb/",
            meaningZh = "v. 控制、抑制（增长、情绪、开支）",
            exampleEn = "The city tried to curb traffic downtown.",
            exampleZh = "市政府想控制市中心的车流。",
            encounterNote = "第 2 次见到它",
        ),
    )

    val quizQuestions = listOf(
        QuizQuestion(
            tag = "语境选词 · 今天学过",
            prompt = "Management was ______ to close the floor.",
            options = listOf("reluctant", "eager", "vivid"),
            answerIndex = 0,
            explanationZh = "reluctant = 不情愿的。管理层不情愿关掉这一层。",
        ),
        QuizQuestion(
            tag = "语法填空 · 现在完成进行时",
            prompt = "She ______ Japanese since last winter.",
            options = listOf("is learning", "has been learning", "learned"),
            answerIndex = 1,
            explanationZh = "since last winter 是「从过去某点到现在」，得用完成进行时。",
        ),
        QuizQuestion(
            tag = "语境选词",
            prompt = "The museum was ______ to the public after two years of renovation.",
            options = listOf("reopened", "reopening", "reopens"),
            answerIndex = 0,
            explanationZh = "被动语态 was + 过去分词：博物馆整修两年后重新向公众开放。",
        ),
    )

    val vocabEntries = listOf(
        VocabEntry("curb", "控制、抑制", KnowledgeStage.Learning, "今天到期 · 上次错了", dueToday = true),
        VocabEntry("linger", "逗留、残留", KnowledgeStage.Learning, "今天到期", dueToday = true),
        VocabEntry("reluctant", "不情愿的", KnowledgeStage.Familiar, "6 天后", dueToday = false),
        VocabEntry("negotiate", "协商", KnowledgeStage.Familiar, "9 天后", dueToday = false),
        VocabEntry("vivid", "生动的", KnowledgeStage.Mastered, "24 天后", dueToday = false),
        VocabEntry("consolidate", "合并、整合", KnowledgeStage.Exposed, "明天首次复习", dueToday = false),
        VocabEntry("plausible", "看似合理的", KnowledgeStage.Exposed, "明天首次复习", dueToday = false),
    )

    val grammarEntries = listOf(
        GrammarEntry("现在完成进行时", KnowledgeStage.Learning, "今天刚学 · 明天复习"),
        GrammarEntry("定语从句 which / that", KnowledgeStage.Learning, "近两次都错"),
        GrammarEntry("现在完成时", KnowledgeStage.Familiar, "5 天后"),
        GrammarEntry("过去进行时", KnowledgeStage.Mastered, "21 天后"),
        GrammarEntry("可数 / 不可数名词", KnowledgeStage.Mastered, "28 天后"),
        GrammarEntry("虚拟语气（基础）", KnowledgeStage.Unseen, "等级到了再排"),
    )

    val recentMaterials = listOf(
        RecentMaterial("The Office That Never Emptied", "8 月 1 日 · 读完"),
        RecentMaterial("粘贴的一段远程办公报道", "7 月 29 日 · 读到一半"),
        RecentMaterial("Why Cities Are Getting Quieter", "7 月 27 日 · 读完"),
    )

    val goalOptions = listOf("日常口语", "工作邮件", "看剧不看字幕", "考试")

    /** 勾兴趣是一次性的粗筛，摆全集反而没人勾；全集在 [TopicCatalog.all]，起始页用。 */
    val topicOptions = TopicCatalog.starter
}

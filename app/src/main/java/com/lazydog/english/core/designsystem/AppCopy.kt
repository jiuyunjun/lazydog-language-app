package com.lazydog.english.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 文案语气（设置页「文案语气」）。
 *
 * 存成字符串而不是枚举，和 `themeMode` 一个路子：偏好里存 wire 值，
 * 解析放在用的地方，`core/data` 不必反过来依赖 `core/designsystem`。
 */
enum class CopyTone(val wire: String, val labelZh: String, val summaryZh: String) {
    Plain("plain", "正常", "安静、直接，把话说清楚"),
    LazyDog("lazydog", "懒狗", "更皮一点，网络用语多一些"),
    ;

    companion object {
        val DEFAULT = Plain
        fun fromWire(wire: String?): CopyTone =
            entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/**
 * 会跟着语气变的那部分文案。
 *
 * **这里只收「氛围文案」**：问候、收尾、空状态、鼓励、按钮上那种可以随便说的话。
 * 下面这些一概不进来，它们在哪种语气下都必须一字不差地直说（`UI_BRIEF.md` §2）：
 *
 * - 错误信息、失败原因、重试提示
 * - 设置项、隐私与密钥说明
 * - 语法术语、词性、CEFR 等级这类名词
 * - AI 提示词
 *
 * 还有两条是[CopyTone.LazyDog]的硬边界，不是风格偏好（`AGENTS.md` §5、
 * `持续学习DESIGN.md` §6/§26/§29）：
 *
 * 1. **不许羞辱、催促、制造焦虑。** 中断几天回来看到的那句尤其——它是恢复流程，
 *    不是催债。"哟还知道回来"这种写法不许出现。
 * 2. **「今天到这里」必须始终是平等选项。** 它可以变皮（"溜了溜了"），
 *    但不能带上一丝罪恶感，也不能被写得比"再学几分钟"低一等。
 *
 * 基类装的就是现在线上的原话，[LazyDogCopy] 只覆盖需要变皮的那些。
 * 这样漏改一条的后果是"这句没变皮"，而不是"这句变成了别的意思"。
 */
open class AppCopy {

    // ---- 今日页 ----

    /** 四步都走完之后的大标题。 */
    open val todayFinishedTitle = "今天的洋屁放完了"

    /** 还没开始 / 学到一半时的问候。 */
    open val todayGreeting = "欢迎回来"

    open val todayMinimumReachedTitle = "今天先到这个量"
    open fun todayPlannedMinutes(minutes: Int) = "今天约 $minutes 分钟"
    open val todayMinimumDone = "今天最低目标已经达成"
    open fun todayMinimumGoal(retrievals: Int) = "今天最低目标：$retrievals 次回忆 · 约 2 分钟"
    open val todayFinishedNote = "复习计划已经更新，明天见。"

    /** 断更几天回来。恢复流程：只说"不用补"，绝不提积压了多少。 */
    open val todayRecoveryNote = "不用补以前的，今天先热身几分钟就好。"

    /** 检测到疲劳。要站在用户那边，不是劝他再撑一会儿。 */
    open val todayFatigueNote = "刚才连着错了几个。累了就是累了，明天的脑子比今天的耐心值钱。"

    open val todayDueSuffix = "到期。先还债，再学新的。"
    open val todayNothingDue = "没有到期的复习，轻松学点新的。"
    open val todayPlanTitle = "今天的顺序"
    open val todayAllStepsDone = "今天的步骤都走完了。想加练随时去「学习」页。"
    open val todaySignOff = "今天到这里。明天见。"
    open val todayKeepGoing = "还想再学一会儿"

    /** 收工按钮。和「再学几分钟」并列，不做次要样式，也不带愧疚。 */
    open val todayStopHere = "今天到这里"

    open val todayStart = "开始今天的学习"
    open val todayReportTitle = "今天学到了什么"
    open fun todayLearned(count: Int) = "新学 $count 个"
    open fun todayRecalled(reviewed: Int, remembered: Int) =
        "回忆 $reviewed 次，想起来 $remembered 次"
    open val todayComebackTitle = "上次没想起来、今天想起来了："
    open fun proofDaysAgo(days: Int) = "$days 天前你还会在这里出错"
    open fun proofPastAnswer(answer: String) = "当时写的是 $answer"
    open fun proofNow(term: String) = "现在：$term · 没用提示"

    // ---- 单词学习 ----

    open val wordNewCardHint = "AI 给你的新词 · 先猜猜意思"
    open fun wordReviewDone(count: Int) = "到期的 $count 个词复习完了"
    open val wordNothingDue = "现在没有到期要复习的词"
    open fun wordAskForNew(count: Int) = "让 AI 来 $count 个新词"
    open val wordStopToday = "今天到这"
    open val wordRoundDone = "这轮搞定"
    open fun wordReviewedCount(count: Int) = "复习了 $count 个词"
    open fun wordLearnedCount(count: Int) = "新学了 $count 个词"
    open val wordLearnedNothing = "什么也没学，也挺好"
    open val wordScheduledNote = "都已经记进复习计划，到期会在记录页出现。"
    open val wordFinish = "收工"

    // ---- 学习页 ----

    open val studyPickNote = "想自己挑就在这儿挑。挑了什么也照样记进复习计划。"
}

/**
 * 懒狗语气。只覆盖需要变皮的，其余继承 [AppCopy] 的原话。
 *
 * 写的时候盯着一条：皮的是**说法**，不是**态度**。可以拿学习这件事开玩笑，
 * 不可以拿用户开玩笑——尤其是他学得少、断更了、答错了的时候。
 */
object LazyDogCopy : AppCopy() {

    override val todayFinishedTitle = "今日份洋屁，已排空"

    // 断更回来看到的是这句。写成"还知道回来"就成了阴阳怪气，那是明令禁止的。
    override val todayGreeting = "回来了"

    override val todayMinimumReachedTitle = "今天就这些，不多给"
    override fun todayPlannedMinutes(minutes: Int) = "今天约 $minutes 分钟，一眨眼的事"
    override val todayMinimumDone = "保底目标达成，可以心安理得地躺了"
    override fun todayMinimumGoal(retrievals: Int) = "今日保底：$retrievals 次回忆 · 两分钟摸完"
    override val todayFinishedNote = "复习计划已经排好了，不用你操心。明天见。"

    override val todayRecoveryNote = "以前的就当没发生过，今天热个身得了。"
    override val todayFatigueNote = "连着错了好几个，脑子该充电了。明天的你比今天的你聪明。"

    override val todayDueSuffix = "到期。先还债，再整新的。"
    override val todayNothingDue = "没有欠债，可以轻装上阵整点新的。"
    override val todayPlanTitle = "今天的流程"
    override val todayAllStepsDone = "今天的活儿全干完了。还想卷就去「学习」页。"
    override val todaySignOff = "收工。明天见。"
    override val todayKeepGoing = "再来亿点点"

    // 依然是平等选项：轻松、干脆，不带一句"就这？"
    override val todayStopHere = "溜了溜了"

    override val todayStart = "开整"
    override val todayReportTitle = "今天到底学到了啥"
    override fun todayLearned(count: Int) = "新学 $count 个"
    override fun todayRecalled(reviewed: Int, remembered: Int) =
        "回忆 $reviewed 次，捞回来 $remembered 次"
    override val todayComebackTitle = "上次卡壳、今天想起来了："
    override fun proofDaysAgo(days: Int) = "$days 天前你还在这儿翻车"
    override fun proofPastAnswer(answer: String) = "当时写的是 $answer"
    override fun proofNow(term: String) = "现在：$term · 全程自己来"

    override val wordNewCardHint = "AI 新整的词 · 先猜猜"
    override fun wordReviewDone(count: Int) = "到期的 $count 个词，清完了"
    override val wordNothingDue = "没有到期的词，债清了"
    override fun wordAskForNew(count: Int) = "让 AI 再整 $count 个新词"
    override val wordStopToday = "溜了"
    override val wordRoundDone = "这轮收工"
    override fun wordReviewedCount(count: Int) = "捡回 $count 个词"
    override fun wordLearnedCount(count: Int) = "新收 $count 个词"
    override val wordLearnedNothing = "一个没学，也不寒碜"
    override val wordScheduledNote = "都记进复习计划了，到点自己会来找你，记录页能看到。"
    override val wordFinish = "撤"

    override val studyPickNote = "想自己挑就自己挑，挑啥都照样进复习计划。"
}

fun copyFor(tone: CopyTone): AppCopy = when (tone) {
    CopyTone.Plain -> PlainCopy
    CopyTone.LazyDog -> LazyDogCopy
}

/** [AppCopy] 基类装的就是正常语气，不用再抄一份。 */
object PlainCopy : AppCopy()

private val LocalAppCopy = staticCompositionLocalOf<AppCopy> { PlainCopy }

/** 取当前语气的文案：`appCopy.todayStopHere`。 */
val appCopy: AppCopy
    @Composable
    @ReadOnlyComposable
    get() = LocalAppCopy.current

/** 挂在 `LazyDogTheme` 外面，和主题一样从偏好读一次、整棵树共用。 */
@Composable
fun ProvideAppCopy(tone: CopyTone, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppCopy provides copyFor(tone), content = content)
}

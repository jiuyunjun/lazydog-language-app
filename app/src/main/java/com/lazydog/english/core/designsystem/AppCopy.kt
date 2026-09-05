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

    override val todayFinishedTitle = "今日洋屁，圆满杀青"

    // 断更回来看到的是这句。写成"还知道回来"就成了阴阳怪气，那是明令禁止的。
    override val todayGreeting = "英语搭子，已就位"

    override val todayMinimumReachedTitle = "今天学到这，刚刚好"
    override fun todayPlannedMinutes(minutes: Int) = "今日英语小份装 · 约 $minutes 分钟"
    override val todayMinimumDone = "最低目标拿下，今日份学习已到账"
    override fun todayMinimumGoal(retrievals: Int) = "今日小目标：$retrievals 次回忆 · 约 2 分钟"
    override val todayFinishedNote = "复习计划已安排，脑子慢慢存档。明天见。"

    override val todayRecoveryNote = "学习进度没跑路，不用补以前的。今天从热身接着来。"
    override val todayFatigueNote = "脑子也有下班权。今天先歇，知识下次再唠。"

    override val todayDueSuffix = "等你复习。老朋友返场，先混个脸熟。"
    override val todayNothingDue = "今天没有到期复习，新知识可以上桌了。"
    override val todayPlanTitle = "今日学习菜单"
    override val todayAllStepsDone = "今日菜单已吃完。想加点知识小零食，去「学习」页逛逛。"
    override val todaySignOff = "今日收工，躺得很有底气。明天见。"
    override val todayKeepGoing = "再学一小口"

    // 依然是平等选项：轻松、干脆，不带一句"就这？"
    override val todayStopHere = "收工，躺会儿"

    override val todayStart = "开整，学两招"
    override val todayReportTitle = "今日份知识到账"
    override fun todayLearned(count: Int) = "新学 $count 个，认识了"
    override fun todayRecalled(reviewed: Int, remembered: Int) =
        "回忆 $reviewed 次，$remembered 次成功对上暗号"
    override val todayComebackTitle = "上次卡住的，这次接上了："
    override fun proofDaysAgo(days: Int) = "$days 天前，这里还没想起来"
    override fun proofPastAnswer(answer: String) = "当时写的是 $answer"
    override fun proofNow(term: String) = "现在：$term · 没看提示，自己想起来的"

    override val wordNewCardHint = "AI 端来新词 · 先猜猜它啥意思"
    override fun wordReviewDone(count: Int) = "到期的 $count 个词，返场复习完毕"
    override val wordNothingDue = "暂无到期单词，复习区先歇会儿"
    override fun wordAskForNew(count: Int) = "让 AI 上 $count 个新词"
    override val wordStopToday = "今天先收工"
    override val wordRoundDone = "这轮学完，漂亮收工"
    override fun wordReviewedCount(count: Int) = "复习 $count 个词，打过照面了"
    override fun wordLearnedCount(count: Int) = "新学 $count 个词，混个脸熟了"
    override val wordLearnedNothing = "这轮先逛逛，下次再学也行"
    override val wordScheduledNote = "复习计划已安排，熟词还会返场。到期去记录页找它们。"
    override val wordFinish = "收工啦"

    override val studyPickNote = "知识自助区，想学哪口挑哪口。学过的照样进复习计划。"
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

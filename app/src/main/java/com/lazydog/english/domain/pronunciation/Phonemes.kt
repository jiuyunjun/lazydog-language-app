package com.lazydog.english.domain.pronunciation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 音位与最小对立的只读目录（`发音与音标学习DESIGN.md` §28，D-077）。
 *
 * 这一层只有数据和查找，没有文件也没有 Android：真正去 assets 里读的是
 * `core/data/AssetPhonemeCatalog`。和 `WordFrequencyIndex → AssetWordFrequencyIndex`
 * 是同一条路子，不新增抽象。
 *
 * **为什么不进数据库**：这些是内容不是状态。用户在一个音位上的表现存在
 * `pronunciation_progress`，音位本身长什么样、怎么发、中国人常错成什么——换一版
 * 词表重装就该整体生效，进了库反而要为「改一句发音描述」写一次迁移。
 */
interface PhonemeCatalog {

    /** 这一版目录用的发音标准。MVP 固定美音，但数据结构不假设永远只有一个。 */
    val accent: AccentProfile

    val phonemes: List<Phoneme>

    val contrasts: List<PhonemeContrast>

    fun phoneme(id: String): Phoneme? = phonemes.firstOrNull { it.id == id }

    fun contrast(id: String): PhonemeContrast? = contrasts.firstOrNull { it.id == id }

    /** 包含这个音位的全部对比组。 */
    fun contrastsOf(phonemeId: String): List<PhonemeContrast> =
        contrasts.filter { it.leftPhonemeId == phonemeId || it.rightPhonemeId == phonemeId }

    /** 目录读不出来时为 true。界面据此显示「音位表读不出来」，而不是显示一个空列表。 */
    val isEmpty: Boolean get() = phonemes.isEmpty()

    companion object {

        /**
         * 读不出来不算错误（`ARCHITECTURE.md` §5「词频表」同款口径）：退化成空目录，
         * 只有这一个模块进不去，App 其余部分照常。
         */
        val Empty: PhonemeCatalog = object : PhonemeCatalog {
            override val accent = AccentProfile.GeneralAmerican
            override val phonemes = emptyList<Phoneme>()
            override val contrasts = emptyList<PhonemeContrast>()
        }
    }
}

/**
 * 发音标准。
 *
 * 「英语固定有 48 个音标」是一种教学分法，不是所有口音共同固定的音位数量（设计文档 §5.1），
 * 所以这里既不写死 48，也不假设永远只有一个 profile。以后要支持英音是**新增一个 profile**，
 * 不是回头改既有音位的含义。
 */
@Serializable
data class AccentProfile(
    val id: String,
    val displayName: String,
    val locale: String,
    val phonemeSetVersion: String,
) {
    companion object {
        val GeneralAmerican = AccentProfile(
            id = "general-american",
            displayName = "美音",
            locale = "en-US",
            phonemeSetVersion = "1",
        )
    }
}

enum class PhonemeCategory(val labelZh: String) {
    Vowel("元音"),
    Consonant("辅音"),
}

@Serializable
data class Phoneme(
    val id: String,
    val ipa: String,
    val category: PhonemeCategory,
    /** 「短元音」「摩擦音」这类分组，只用于总览页分节，不参与任何判定。 */
    @SerialName("group") val groupZh: String,
    val displayOrder: Int = 0,
    @SerialName("shortDescription") val shortDescriptionZh: String,
    val articulation: ArticulationGuide,
    /** 中文母语者常见误读。这是核心内容，不是附加内容（设计文档 §9）。 */
    val commonErrors: List<CommonError> = emptyList(),
    val exampleWords: List<ExampleWord> = emptyList(),
    val contrastIds: List<String> = emptyList(),
)

/**
 * 发音动作。
 *
 * 写法上先给动作再给术语（设计文档 §8.3）：「舌尖轻搭在上下齿之间」在前，
 * 「齿间摩擦音」收在 [termZh] 里，由界面折起来。只写术语的说明对着镜子照不出来。
 */
@Serializable
data class ArticulationGuide(
    @SerialName("tongue") val tongueZh: String = "",
    @SerialName("lips") val lipsZh: String = "",
    @SerialName("jaw") val jawZh: String = "",
    @SerialName("airflow") val airflowZh: String = "",
    @SerialName("voicing") val voicingZh: String = "",
    /** 只记一句话的话记这句。界面上它是高亮的那一条。 */
    @SerialName("keyAction") val keyActionZh: String,
    @SerialName("term") val termZh: String = "",
)

/**
 * 一条常见误读：目标音被替换成了什么、结果那个词听起来变成了什么、怎么改回来。
 *
 * 四段缺一不可（设计文档 §9）：只说「/θ/ 容易读成 /s/」用户不知道后果，
 * 只说「think 会变成 sink」用户不知道该动哪儿。
 */
@Serializable
data class CommonError(
    @SerialName("substitute") val substituteIpa: String,
    val exampleEn: String,
    val soundsLikeEn: String,
    @SerialName("fix") val fixZh: String,
)

@Serializable
data class ExampleWord(val word: String, val ipa: String)

@Serializable
data class MinimalPair(
    val leftWord: String,
    val rightWord: String,
    val leftIpa: String,
    val rightIpa: String,
    @SerialName("note") val noteZh: String = "",
) {
    fun wordFor(side: PairSide): String = if (side == PairSide.Left) leftWord else rightWord

    fun ipaFor(side: PairSide): String = if (side == PairSide.Left) leftIpa else rightIpa
}

enum class PairSide { Left, Right }

@Serializable
data class PhonemeContrast(
    val id: String,
    val leftPhonemeId: String,
    val rightPhonemeId: String,
    /** 0～1，越大越难。只用于冷启动排序；有了真实作答之后由表现说话。 */
    val difficulty: Float = 0.5f,
    /**
     * 最小对立词表。至少要有 3 对，否则用户记住的是「ship 那道题选左边」
     * 而不是这两个音的差别（设计文档 §10.3）。
     */
    val minimalPairs: List<MinimalPair> = emptyList(),
)

/** assets 里那份 JSON 的顶层结构。 */
@Serializable
data class PhonemeCatalogPayload(
    val accent: AccentProfile,
    val phonemes: List<Phoneme> = emptyList(),
    val contrasts: List<PhonemeContrast> = emptyList(),
)

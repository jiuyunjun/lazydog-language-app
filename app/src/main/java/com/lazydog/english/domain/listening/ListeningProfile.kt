package com.lazydog.english.domain.listening

/**
 * 一次跨轮次保留下来的作答，是 `listening_attempts` 去掉存储细节之后的样子。
 */
data class ListeningRecord(
    val correct: Boolean,
    val playCount: Int,
    val hintLevel: ListeningHintLevel,
    /** 这句的听觉难点标签。一句可以同时命中好几个。 */
    val audioFeatures: List<String>,
    /** 答错时选的那条干扰项属于哪类误听。 */
    val mishearType: MishearType?,
    val score: Int,
)

/**
 * 能力地图的第二层（`持续学习DESIGN.md` §16）。
 *
 * 第一层"听力 A2+"只能告诉用户他大概在哪儿；第二层要回答的是
 * **哪里变强了、哪里还弱**——连读 43%、数字 95%，这才指得动下一步练什么。
 */
data class ListeningProfile(
    val attempts: Int,
    val correct: Int,
    /** 无提示一遍就听懂的次数：比"正确率"更接近真实听力。 */
    val firstListen: Int,
    /** 每个听觉难点各自的成绩，样本少的排后面。 */
    val features: List<FeatureScore>,
    /** 最常栽的误听类型，多到少。 */
    val mishears: List<MishearCount>,
) {
    val percent: Int? get() = if (attempts < MIN_ATTEMPTS_FOR_PROFILE) null else correct * 100 / attempts

    companion object {
        val Empty = ListeningProfile(0, 0, 0, emptyList(), emptyList())
    }
}

data class FeatureScore(val feature: String, val attempts: Int, val correct: Int) {
    val percent: Int get() = if (attempts == 0) 0 else correct * 100 / attempts
}

data class MishearCount(val type: MishearType, val count: Int)

/** 少于这么多次不出总正确率：三五道题的正确率是噪声，摆出来只会误导。 */
const val MIN_ATTEMPTS_FOR_PROFILE = 10

/** 单个听觉难点少于这么多次不单独下结论，但仍然列出来告诉用户"这一项还没练够"。 */
const val MIN_ATTEMPTS_PER_FEATURE = 3

/**
 * 把历史作答聚合成画像。[records] 是全部历史，越多越准。
 *
 * 注意"答对"的口径：**用了提示答对仍然算答对**。提示的代价已经体现在分数里，
 * 在这里再罚一次等于同一件事扣两遍。要看真实听力就看 [ListeningProfile.firstListen]。
 */
fun listeningProfile(records: List<ListeningRecord>): ListeningProfile {
    if (records.isEmpty()) return ListeningProfile.Empty

    val features = records
        .flatMap { record -> record.audioFeatures.map { it to record.correct } }
        .groupBy({ it.first }, { it.second })
        .map { (feature, results) ->
            FeatureScore(feature = feature, attempts = results.size, correct = results.count { it })
        }
        // 先按样本够不够分两档，再按正确率升序——最弱的那一项排在最前面，那是最该练的。
        .sortedWith(compareByDescending<FeatureScore> { it.attempts >= MIN_ATTEMPTS_PER_FEATURE }
            .thenBy { it.percent })

    val mishears = records.mapNotNull { it.mishearType }
        .groupingBy { it }
        .eachCount()
        .map { (type, count) -> MishearCount(type, count) }
        .sortedByDescending { it.count }

    return ListeningProfile(
        attempts = records.size,
        correct = records.count { it.correct },
        firstListen = records.count {
            it.correct && it.hintLevel == ListeningHintLevel.None && it.playCount <= 1
        },
        features = features,
        mishears = mishears,
    )
}

package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.VocabularySenseImageEntity
import com.lazydog.english.core.network.ImageDownloader
import com.lazydog.english.core.network.ImageSearchProvider
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.vocabulary.ImageFailureReason
import com.lazydog.english.domain.vocabulary.ImageStrategy
import com.lazydog.english.domain.vocabulary.SenseKey
import com.lazydog.english.domain.vocabulary.SenseVisualState
import com.lazydog.english.domain.vocabulary.VisualCandidateFilter
import com.lazydog.english.domain.vocabulary.VisualSearchPlan
import com.lazydog.english.domain.vocabulary.VisualSearchRequest
import com.lazydog.english.domain.vocabulary.VocabularyImageAsset
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 单词视觉记忆图片的读写与发现入口（`单词视觉记忆图片DESIGN.md` §61）。
 *
 * 整条链路都在这里：
 * `词义 → 能不能画 → 检索词 → Brave → 本地过滤 → Top 3 → 落库`。
 * 页面只说"给这个词义找张图"或者"这张不行，换一批"，不需要知道中间有几次网络调用。
 *
 * 三条不变量：
 * 1. **缓存以词义为单位**，key 是 [SenseKey]，不是词形（§24）。
 * 2. **失败不清空已有内容**：搜不到就是这次没搜到，原来选中的那张还留着（和记忆提示同一条规矩）。
 * 3. **不无限扩大搜索**：一个词义最多发三次请求（主查询 + 两个备用），之后就认了（§29）。
 *
 * 选中的那一张会下载到本地留一份（[downloader]，D-076）：外链会失效，
 * Coil 的磁盘缓存又是系统随时可以回收的空间，两者都不足以让"我挑好的那张图"活到下次复习。
 * 没配下载器（单测）或者下载失败时一切照旧走外链，不当作错误。
 */
class VocabularyImageRepository(
    private val database: AppDatabase,
    private val generator: LearningContentGenerator,
    private val search: ImageSearchProvider,
    private val downloader: ImageDownloader? = null,
    private val now: () -> Instant = Instant::now,
) {
    private val dao = database.vocabularyImageDao()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 同一个词义同时只跑一条发现流程。
     *
     * 词卡和阅读预取可能同时想起同一个词——不挡的话就是两倍的模型调用和两倍的搜索配额，
     * 而且后写的那次会盖掉先写的结果。
     */
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightGuard = Mutex()

    fun observe(senseKey: SenseKey): Flow<SenseVisualState?> =
        dao.observe(senseKey.value).map { it?.toDomain() }

    suspend fun get(senseKey: SenseKey): SenseVisualState? = dao.get(senseKey.value)?.toDomain()

    /**
     * 缓存里有就用缓存，没有才去找（§64：打开词卡不该现跑 `模型 → Brave`）。
     *
     * [force] 是用户点了「都不合适，重新找」：换一批检索词重搜，并清掉上次的失败原因。
     * 用户说过「这个词不用配图」时除非 [force] 否则不动——那是他的决定，不该被下次打开覆盖。
     */
    suspend fun ensure(
        senseKey: SenseKey,
        term: String,
        meaningZh: String,
        pos: String = "",
        exampleEn: String = "",
        force: Boolean = false,
    ): SenseVisualState {
        val lock = inFlightGuard.withLock { inFlight.getOrPut(senseKey.value) { Mutex() } }
        return lock.withLock {
            val cached = dao.get(senseKey.value)?.toDomain()
            if (cached != null && !force) {
                // 只有"这次没搜到"值得重试，"这个词义不该有图"和"用户关掉了"都是结论。
                if (cached.hiddenByUser || cached.hasImage || cached.failure == ImageFailureReason.NotVisualizable) {
                    return@withLock cached
                }
            }
            if (cached?.hiddenByUser == true && !force) return@withLock cached
            discover(senseKey, term, meaningZh, pos, exampleEn, previous = cached)
        }
    }

    /**
     * 文章生成完之后给本篇的目标词预取（§49）。
     *
     * 只补没有的，不重搜已有的；已经关掉配图的词义也跳过。
     * 熟词不该进这个列表——那是调用方的判断，这里不猜。
     */
    suspend fun prefetch(targets: List<PrefetchTarget>) {
        if (targets.isEmpty() || !search.isConfigured()) return
        val known = dao.existing(targets.map { it.senseKey.value }).toSet()
        targets.filter { it.senseKey.value !in known }.forEach { target ->
            ensure(
                senseKey = target.senseKey,
                term = target.term,
                meaningZh = target.meaningZh,
                pos = target.pos,
                exampleEn = target.exampleEn,
            )
        }
    }

    /** 用户在「换一张」里挑了另一张。记下来——这比模型自评分更能说明默认那张不好用（§40）。 */
    suspend fun select(senseKey: SenseKey, index: Int) {
        val entity = dao.get(senseKey.value) ?: return
        val assets = decodeAssets(entity.assetsJson)
        if (index !in assets.indices) return
        // 换成这张之后它才是"用户挑定的那张"，这时候才值得下载；
        // 上一张的本地副本留着——换回去是常事，删了就要重下（§40）。
        dao.save(
            entity.copy(
                assetsJson = encodeAssets(downloadSelected(assets, index)),
                selectedIndex = index,
                replacedByUser = entity.replacedByUser || index != entity.selectedIndex,
                updatedAt = now().toEpochMilli(),
            ),
        )
    }

    /**
     * 草稿卡入库了，把图搬到正式的词义键上（§24 的草稿键就是为了这一步）。
     *
     * 不搬的话：预览时挑好的图在按下「添加」之后查不到，详情页会重跑一遍
     * `模型 → Brave`，用户刚做的选择白做。目标键已经有图时不覆盖——
     * 那多半是同一个词义又被添加了一次，已入库的那份更可信。
     */
    suspend fun adoptDraft(draftKey: SenseKey, itemId: Long) {
        val target = SenseKey.of(itemId)
        if (draftKey.value == target.value) return
        val draft = dao.get(draftKey.value) ?: return
        if (dao.get(target.value) == null) {
            dao.save(draft.copy(senseKey = target.value, updatedAt = now().toEpochMilli()))
            // 本地副本的路径跟着记录走了，这里只删草稿这条索引，不动文件。
            dao.delete(draftKey.value)
        } else {
            deleteLocalCopies(decodeAssets(draft.assetsJson))
            dao.delete(draftKey.value)
        }
    }

    /**
     * 显示时发现这张图挂了（404、站点拒绝、地址过期），自动顶下一张（§37）。
     * 候选全挂了才落到"这次没有图"，而不是一直转圈。
     */
    suspend fun dropBroken(senseKey: SenseKey, index: Int) {
        val entity = dao.get(senseKey.value) ?: return
        val assets = decodeAssets(entity.assetsJson)
        if (index !in assets.indices) return
        val remaining = assets.filterIndexed { i, _ -> i != index }
        // 这张已经确认打不开，本地那份（如果下过）同样没用了。
        deleteLocalCopies(listOf(assets[index]))
        dao.save(
            entity.copy(
                assetsJson = encodeAssets(
                    if (remaining.isEmpty()) remaining else downloadSelected(remaining, 0),
                ),
                selectedIndex = 0,
                failureReason = if (remaining.isEmpty()) ImageFailureReason.NoResults.name else "",
                updatedAt = now().toEpochMilli(),
            ),
        )
    }

    /** 「这个词不用配图」。按词义记，同一个词的别的意思不受影响。 */
    suspend fun hide(senseKey: SenseKey, term: String = "", meaningZh: String = "") {
        val entity = dao.get(senseKey.value)
        dao.save(
            entity?.copy(hiddenByUser = true, updatedAt = now().toEpochMilli())
                ?: empty(senseKey, term, meaningZh).copy(hiddenByUser = true),
        )
    }

    suspend fun unhide(senseKey: SenseKey) {
        val entity = dao.get(senseKey.value) ?: return
        dao.save(entity.copy(hiddenByUser = false, updatedAt = now().toEpochMilli()))
    }

    /** ⋯ 里的「不相关 / 太抽象 / 看不懂 / 质量差」。只记，不当场改排序（§41、§43）。 */
    suspend fun recordFeedback(senseKey: SenseKey, reason: String) {
        val entity = dao.get(senseKey.value) ?: return
        dao.save(entity.copy(feedbackReason = reason, updatedAt = now().toEpochMilli()))
    }

    /** 备份用：整表导出。 */
    suspend fun exportAll(): List<VocabularySenseImageEntity> = dao.getAll()

    // ---- 发现流程 ----

    private suspend fun discover(
        senseKey: SenseKey,
        term: String,
        meaningZh: String,
        pos: String,
        exampleEn: String,
        previous: SenseVisualState?,
    ): SenseVisualState {
        // 没有密钥不是错误，是"这个功能这次不参加"——和联网检索同一个口径。
        if (!search.isConfigured()) {
            return previous ?: fail(senseKey, term, meaningZh, ImageFailureReason.NotConfigured, persist = false)
        }

        val planResult = generator.generateVisualSearchPlan(
            VisualSearchRequest(term = term, meaningZh = meaningZh, pos = pos, exampleEn = exampleEn),
        )
        val plan = when (planResult) {
            is GenerationResult.Success -> planResult.data
            is GenerationResult.Failure ->
                return previous ?: fail(senseKey, term, meaningZh, ImageFailureReason.ApiFailure, persist = false)
        }
        val model = (planResult as GenerationResult.Success).model
        val promptVersion = planResult.promptVersion

        // 「这个词义画不出来」要落库：否则每次打开词卡都会为 although 再问一次模型。
        if (!plan.visualizable) {
            return fail(senseKey, term, meaningZh, ImageFailureReason.NotVisualizable, model, promptVersion)
        }

        var lastFailure = ImageFailureReason.NoResults
        // §29：主查询 → 备用 A → 备用 B，最多三次，之后就认了。
        for (query in plan.queries) {
            val result = search.search(query)
            if (result.failure != null) {
                lastFailure = ImageFailureReason.ApiFailure
                continue
            }
            val ranked = VisualCandidateFilter.rank(
                candidates = result.hits,
                term = term,
                mustShow = plan.mustShow,
                avoid = plan.avoid,
            )
            if (ranked.isEmpty()) {
                lastFailure = ImageFailureReason.LowSemanticMatch
                continue
            }
            // 重搜是覆盖，上一批的本地副本没有记录再指向它们，留着就是垃圾文件。
            previous?.let { deleteLocalCopies(it.assets) }
            val entity = VocabularySenseImageEntity(
                senseKey = senseKey.value,
                term = term,
                meaningZh = meaningZh,
                strategy = plan.strategy.name,
                query = query,
                visualTarget = plan.visualTarget,
                assetsJson = encodeAssets(downloadSelected(ranked, 0)),
                selectedIndex = 0,
                failureReason = "",
                hiddenByUser = false,
                feedbackReason = "",
                replacedByUser = false,
                model = model,
                promptVersion = promptVersion,
                updatedAt = now().toEpochMilli(),
            )
            dao.save(entity)
            return entity.toDomain()
        }
        return fail(senseKey, term, meaningZh, lastFailure, model, promptVersion, plan)
    }

    /**
     * 记下这次为什么没有图。
     *
     * [persist] 为 false 的那两种（没配密钥、模型这次没答上来）不落库：它们和这个词义无关，
     * 存下来只会让下次打开时以为这个词天生没图。
     */
    private suspend fun fail(
        senseKey: SenseKey,
        term: String,
        meaningZh: String,
        reason: ImageFailureReason,
        model: String = "",
        promptVersion: Int = 0,
        plan: VisualSearchPlan? = null,
        persist: Boolean = true,
    ): SenseVisualState {
        val entity = empty(senseKey, term, meaningZh).copy(
            strategy = (plan?.strategy ?: ImageStrategy.None).name,
            query = plan?.primaryQuery.orEmpty(),
            visualTarget = plan?.visualTarget.orEmpty(),
            failureReason = reason.name,
            model = model,
            promptVersion = promptVersion,
        )
        if (persist) dao.save(entity)
        return entity.toDomain()
    }

    private fun empty(senseKey: SenseKey, term: String, meaningZh: String) = VocabularySenseImageEntity(
        senseKey = senseKey.value,
        term = term,
        meaningZh = meaningZh,
        strategy = ImageStrategy.None.name,
        query = "",
        visualTarget = "",
        assetsJson = "[]",
        selectedIndex = 0,
        failureReason = "",
        hiddenByUser = false,
        feedbackReason = "",
        replacedByUser = false,
        model = "",
        promptVersion = 0,
        updatedAt = now().toEpochMilli(),
    )

    /**
     * 把第 [index] 张下到本地，返回改好 `localPath` 的整批候选。
     *
     * 下载失败就原样返回：外链还在，图照常显示，只是这次没留下本地副本——
     * 和"没搜到"不一样，这不值得让用户看见任何东西（D-076）。
     */
    private suspend fun downloadSelected(
        assets: List<VocabularyImageAsset>,
        index: Int,
    ): List<VocabularyImageAsset> {
        val store = downloader ?: return assets
        val asset = assets.getOrNull(index) ?: return assets
        if (asset.localPath.isNotBlank()) return assets
        val path = store.download(asset.thumbnailUrl.ifBlank { asset.originalUrl }) ?: return assets
        return assets.mapIndexed { i, item -> if (i == index) item.copy(localPath = path) else item }
    }

    private suspend fun deleteLocalCopies(assets: List<VocabularyImageAsset>) {
        val store = downloader ?: return
        assets.forEach { if (it.localPath.isNotBlank()) store.delete(it.localPath) }
    }

    private fun encodeAssets(assets: List<VocabularyImageAsset>): String =
        json.encodeToString(ListSerializer(VocabularyImageAsset.serializer()), assets)

    private fun decodeAssets(raw: String): List<VocabularyImageAsset> =
        runCatching { json.decodeFromString(ListSerializer(VocabularyImageAsset.serializer()), raw) }
            .getOrDefault(emptyList())

    private fun VocabularySenseImageEntity.toDomain(): SenseVisualState {
        val assets = decodeAssets(assetsJson)
        return SenseVisualState(
            senseKey = senseKey,
            term = term,
            meaningZh = meaningZh,
            strategy = runCatching { ImageStrategy.valueOf(strategy) }.getOrDefault(ImageStrategy.None),
            query = query,
            visualTarget = visualTarget,
            assets = assets,
            selectedIndex = selectedIndex.coerceIn(0, (assets.size - 1).coerceAtLeast(0)),
            failure = ImageFailureReason.entries.firstOrNull { it.name == failureReason },
            hiddenByUser = hiddenByUser,
        )
    }

    /** 预取一个词义要知道的最少信息。 */
    data class PrefetchTarget(
        val senseKey: SenseKey,
        val term: String,
        val meaningZh: String,
        val pos: String = "",
        val exampleEn: String = "",
    )
}

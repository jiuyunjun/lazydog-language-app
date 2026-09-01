package com.lazydog.english.core.ai

import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.network.OpenAiCompatClient
import com.lazydog.english.core.network.looksLikeChatModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 服务商支持的模型清单，应用级共享。
 *
 * 放在应用层而不是页面里，是因为「各功能使用的模型」要在两级页面之间来回翻：
 * 每翻一次就重新拉一遍列表，既慢又白花请求。拉到一次就留着，直到用户主动刷新。
 */
class ModelCatalog(private val prefs: UserPreferences) {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        /** [chat] 是筛过的对话模型，[all] 是服务端原样返回的全部。 */
        data class Loaded(val chat: List<String>, val all: List<String>) : State
        data class Failed(val reason: String) : State
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 已经拉到过就不再拉，除非 [force]。 */
    suspend fun load(force: Boolean = false) = mutex.withLock {
        if (!force && _state.value is State.Loaded) return@withLock
        _state.value = State.Loading
        val client = OpenAiCompatClient(
            baseUrl = prefs.aiBaseUrl.first(),
            apiKey = prefs.aiApiKey.first(),
        )
        _state.value = when (val result = client.listModels()) {
            is OpenAiCompatClient.ModelsResult.Success -> {
                val chat = result.models.filter(::looksLikeChatModel)
                // 全被筛掉说明这套命名不吃这一版规则，那就别自作聪明，原样给出来。
                State.Loaded(chat = chat.ifEmpty { result.models }, all = result.models)
            }
            is OpenAiCompatClient.ModelsResult.Failure -> State.Failed(result.reason)
        }
    }
}

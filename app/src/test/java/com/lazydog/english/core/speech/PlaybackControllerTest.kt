package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeakResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态机的行为约定，对应 `语音服务DESIGN.md` §12（交互规则）、§15/§16（generation 校验）
 * 和 §18（连点取最后一次）。真实设备上这些场景全靠手点很难复现，所以在这里钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val a = PlaybackSource.word("territory")
    private val b = PlaybackSource.word("terrain")
    private val c = PlaybackSource.word("terrace")

    @Test
    fun `一次播放走 加载 到 播放 到 空闲`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.onPlayClicked(a)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Loading, controller.state.value.status)
        assertEquals(a.id, controller.state.value.sourceId)

        tts.start(0)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Playing, controller.state.value.status)

        // 合成完成不等于播放完成：只有底层把缓冲放空返回了，状态才回到空闲（§22）。
        tts.finish(0, SpeakResult.Done)
        advanceUntilIdle()
        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `同一个按钮再点一次就是停`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.onPlayClicked(a)
        advanceUntilIdle()
        tts.start(0)
        advanceUntilIdle()

        controller.onPlayClicked(a)
        advanceUntilIdle()
        assertEquals(PlaybackState.Idle, controller.state.value)
        assertEquals(1, tts.stops)
        // 停了不该再起一段新的。
        assertEquals(1, tts.jobs.size)
    }

    @Test
    fun `点别的按钮立刻换成新的一段，不等旧的收尾`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.onPlayClicked(a)
        advanceUntilIdle()
        tts.start(0)
        advanceUntilIdle()

        controller.onPlayClicked(b)
        advanceUntilIdle()
        // A 还没返回，B 已经在加载了（§14）。
        assertEquals(PlaybackStatus.Loading, controller.state.value.status)
        assertEquals(b.id, controller.state.value.sourceId)
        assertEquals(2, tts.jobs.size)

        // A 的收尾迟到了，不能把状态拽回去（§16）。
        tts.finish(0, SpeakResult.Done)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Loading, controller.state.value.status)
        assertEquals(b.id, controller.state.value.sourceId)

        tts.start(1)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Playing, controller.state.value.status)
        assertEquals(b.id, controller.state.value.sourceId)
    }

    @Test
    fun `连点取最后一次`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.play(a)
        controller.play(b)
        controller.play(c)
        controller.play(a)
        advanceUntilIdle()

        assertEquals(4, tts.jobs.size)
        assertEquals(a.id, controller.state.value.sourceId)

        // 前三段陆续返回，谁都不该改状态。
        tts.finish(0, SpeakResult.Done)
        tts.finish(1, SpeakResult.Failed("网断了"))
        tts.finish(2, SpeakResult.Done)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Loading, controller.state.value.status)
        assertEquals(a.id, controller.state.value.sourceId)

        tts.start(3)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `迟到的出声回调不会把别人的按钮点亮`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.onPlayClicked(a)
        advanceUntilIdle()
        controller.onPlayClicked(b)
        advanceUntilIdle()

        tts.start(0)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Loading, controller.state.value.status)
        assertEquals(b.id, controller.state.value.sourceId)
    }

    @Test
    fun `失败进错误态，再点一次可以重来`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.onPlayClicked(a)
        advanceUntilIdle()
        tts.finish(0, SpeakResult.Failed("示范音频失败：网断了"))
        advanceUntilIdle()

        assertEquals(PlaybackStatus.Error, controller.state.value.status)
        assertEquals("示范音频失败：网断了", controller.state.value.error?.message)
        assertEquals(a.id, controller.state.value.sourceId)
        // 出错的按钮问自己是错误态，别人问就是空闲。
        assertEquals(PlaybackStatus.Idle, controller.state.value.statusOf(b.id))

        controller.onPlayClicked(a)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.Loading, controller.state.value.status)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `停下时错误提示一起清掉`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.onPlayClicked(a)
        advanceUntilIdle()
        tts.finish(0, SpeakResult.Failed("网断了"))
        advanceUntilIdle()

        controller.stop()
        advanceUntilIdle()
        assertEquals(PlaybackState.Idle, controller.state.value)
        assertTrue(tts.stops > 0)
    }

    @Test
    fun `空闲时开始播不会去掐本来就没在响的声音`() = runTest {
        val tts = FakeTts()
        val controller = PlaybackController(TestScope(testScheduler), tts)

        controller.play(a)
        advanceUntilIdle()
        // 多掐一次会让底层以为链路是热的，冷起播的前置静音被砍短，开头容易吞字。
        assertEquals(0, tts.stops)
    }

    private class FakeTts : TtsPlayback {

        val jobs = mutableListOf<PlaybackJob>()
        var stops = 0
            private set

        private val starts = mutableListOf<() -> Unit>()
        private val results = mutableListOf<CompletableDeferred<SpeakResult>>()

        override suspend fun speak(job: PlaybackJob, onStarted: () -> Unit): SpeakResult {
            jobs += job
            starts += onStarted
            val result = CompletableDeferred<SpeakResult>()
            results += result
            return result.await()
        }

        override fun stop(keepLink: Boolean) {
            stops += 1
        }

        /** 第 [index] 段音频真的出声了。 */
        fun start(index: Int) = starts[index].invoke()

        /** 第 [index] 段播完（或失败）返回。 */
        fun finish(index: Int, result: SpeakResult) {
            results[index].complete(result)
        }
    }
}

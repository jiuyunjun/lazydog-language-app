# Azure Speech SDK 音频架构设计

## 1. 目标

本文定义应用内 Azure Speech SDK 的 TTS / STT 集成方式，以及统一的音频播放状态机。

设计目标：

- 降低 TTS 播放卡顿、吞音、重复播放、旧音频残留等问题。
- 避免业务层直接操作 Azure Speech SDK、`AudioTrack`、`AudioRecord`。
- 将“语音服务”和“音频 I/O”解耦。
- 明确连续点击同一播放按钮、切换其他播放按钮、快速连续点击时的行为。
- 正确处理异步回调、取消、音频缓冲、Audio Focus、蓝牙切换等复杂场景。
- 为后续接入其他 TTS / STT Provider 保留扩展能力。

---

# 2. 核心设计原则

## 2.1 Azure Speech SDK 负责语音能力，系统音频框架负责播放和录音

推荐：

```text
TTS:
Azure Speech
    ↓
PCM Stream
    ↓
AudioBufferQueue
    ↓
Native Audio Player
    ↓
Speaker
```

```text
STT:
Microphone
    ↓
Native Audio Recorder
    ↓
PCM Processor
    ↓
Azure PushAudioInputStream
    ↓
SpeechRecognizer
```

不推荐在生产架构中长期依赖：

```text
Azure SDK → default speaker
Azure SDK ← default microphone
```

默认 Speaker / Microphone 适合 Demo 或简单功能，但复杂 App 中会降低对以下能力的控制：

- 播放停止
- 播放切换
- 播放队列
- Audio Focus
- 蓝牙设备切换
- App 前后台切换
- 来电中断
- 音频缓冲
- 播放状态
- Barge-in
- AEC
- 录音保存
- 波形显示
- VAD

---

# 3. 推荐模块划分

```text
speech/
├── SpeechService
│
├── tts/
│   ├── TtsClient
│   ├── AzureSpeechTtsClient
│   ├── TtsRequest
│   ├── TtsResult
│   └── TtsSession
│
├── stt/
│   ├── SttClient
│   ├── AzureSpeechSttClient
│   ├── RecognitionSession
│   └── TranscriptState
│
├── playback/
│   ├── PlaybackController
│   ├── PlaybackJob
│   ├── PlaybackState
│   └── PlaybackEvent
│
└── audio/
    ├── AudioPlayer
    ├── AudioRecorder
    ├── AudioBufferQueue
    ├── AudioProcessor
    ├── AudioFocusManager
    └── AudioFormat
```

依赖方向：

```text
UI
↓
PlaybackController / SpeechService
↓
TtsClient / SttClient / AudioPlayer / AudioRecorder
↓
Azure Speech SDK / Android Audio APIs
```

业务层不得直接：

```text
new SpeechSynthesizer(...)
new SpeechRecognizer(...)
new AudioTrack(...)
new AudioRecord(...)
```

所有音频生命周期统一由 Speech / Playback 层管理。

---

# 4. Audio Contract

所有模块必须使用明确的内部音频格式，禁止不同模块自行猜测采样率。

## 4.1 TTS

推荐内部格式：

```text
Sample Rate : 24000 Hz
Bit Depth   : 16-bit
Channels    : Mono
Encoding    : PCM signed little-endian
```

即：

```text
PCM_S16LE / 24 kHz / Mono
```

适合语音播放，质量和数据量比较平衡。

## 4.2 STT

推荐送入 Azure 的格式：

```text
Sample Rate : 16000 Hz
Bit Depth   : 16-bit
Channels    : Mono
Encoding    : PCM signed little-endian
```

即：

```text
PCM_S16LE / 16 kHz / Mono
```

如果设备录音原生格式为：

```text
48 kHz / Mono
```

则统一经过：

```text
AudioRecord
↓
AudioProcessor
↓
Resample 48k → 16k
↓
Azure STT
```

不要让不同 STT Provider 各自处理未经规范化的设备音频。

---

# 5. TTS 架构

## 5.1 不直接使用 Azure 作为最终播放器

推荐：

```text
SpeechSynthesizer
↓
PCM Stream
↓
AudioBufferQueue
↓
AudioPlayer
↓
AudioTrack
```

Azure 的职责：

- 请求合成
- 获取音频数据
- 提供 requestId
- 提供合成事件
- 提供 cancellation/error

AudioPlayer 的职责：

- prebuffer
- playback
- pause
- stop
- clear
- drain
- underrun 监控
- Audio Focus
- output route

---

# 6. 流式播放

禁止：

```text
请求完整 TTS
↓
等待全部音频生成
↓
一次性播放
```

推荐：

```text
Azure
↓
audio chunk
↓
AudioBufferQueue
↓
prebuffer
↓
AudioTrack.write()
```

建议采用生产者 / 消费者模型：

```text
Azure Reader
     ↓
BlockingQueue<AudioChunk>
     ↓
Audio Playback Worker
     ↓
AudioTrack
```

不要在 Azure callback 内直接执行耗时的：

```text
AudioTrack.write(...)
```

否则网络回调线程和播放线程会被耦合。

---

# 7. Prebuffer

TTS 流式播放不能收到第一个很小的 chunk 就立刻播放。

推荐：

```text
Azure
↓
80~150 ms PCM prebuffer
↓
开始播放
↓
后续持续补充
```

原因：

Azure / 网络 chunk 到达时间并不严格均匀。

如果 buffer 太小：

```text
chunk 1
↓
立即播放

chunk 2 延迟
↓
buffer empty
↓
underrun
↓
卡顿 / 爆音 / 吞音
```

如果 buffer 太大，例如：

```text
2 秒
```

虽然稳定，但会显著增加用户感知延迟。

目标应优先保持：

```text
低首音延迟 + 无 underrun
```

---

# 8. SpeechSynthesizer 生命周期

不推荐：

```java
void speak(String text) {
    SpeechSynthesizer synthesizer = new SpeechSynthesizer(...);
    synthesizer.SpeakTextAsync(text);
}
```

推荐：

```text
Speech Session
↓
创建 SpeechSynthesizer
↓
复用
↓
Session / Service 销毁时统一释放
```

避免每句话：

```text
connect
synthesize
disconnect
connect
synthesize
disconnect
```

否则可能造成：

- 首包延迟增加
- WebSocket 重连
- 高频对象创建
- 生命周期竞争
- cancel 行为复杂化

---

# 9. TTS 与 Playback 生命周期必须分离

必须明确：

```text
SynthesisCompleted
≠
PlaybackCompleted
```

例如：

```text
Azure:
100% 音频已经生成

AudioTrack:
可能只播放到 70%
```

因此至少存在两套内部状态：

```text
TtsState
- IDLE
- SYNTHESIZING
- COMPLETED
- CANCELED
- ERROR
```

```text
AudioState
- IDLE
- BUFFERING
- PLAYING
- DRAINING
- STOPPED
- ERROR
```

UI 不直接消费这两套底层状态。

由 `PlaybackController` 聚合为业务状态。

---

# 10. PlaybackState

对业务层暴露：

```text
PlaybackState
- IDLE
- LOADING
- PLAYING
- ERROR
```

内部允许额外存在：

```text
STOPPING
DRAINING
CANCELLING
```

但通常无需暴露给 UI。

推荐数据结构：

```java
class PlaybackState {
    Status status;

    String sourceId;
    String jobId;

    long generation;

    PlaybackError error;
}
```

---

# 11. PlaybackJob

每一次播放请求必须产生新的 Job。

```java
class PlaybackJob {
    String jobId;
    String sourceId;

    String text;

    VoiceConfig voice;

    long generation;
}
```

必须区分：

```text
sourceId
```

和：

```text
jobId
```

例如：

```text
第一次点击 territory:
sourceId = word:territory
jobId    = 001

停止后再次点击 territory:
sourceId = word:territory
jobId    = 002
```

两个任务的 `sourceId` 一样，但 `jobId` 必须不同。

---

# 12. 播放按钮交互规则

应用默认采用：

```text
same source      → toggle stop
different source → replace current
```

即：

## 12.1 当前没有播放

```text
IDLE
+
click A
↓
LOADING(A)
↓
PLAYING(A)
```

## 12.2 正在播放 A，再点击 A

```text
PLAYING(A)
+
click A
↓
STOP A
↓
IDLE
```

## 12.3 A 正在 Loading，再点击 A

```text
LOADING(A)
+
click A
↓
CANCEL A
↓
IDLE
```

## 12.4 正在播放 A，点击 B

```text
PLAYING(A)
+
click B
↓
invalidate A
↓
stop A immediately
↓
LOADING(B)
↓
PLAYING(B)
```

## 12.5 A 正在 Loading，点击 B

```text
LOADING(A)
+
click B
↓
invalidate A
↓
cancel A
↓
LOADING(B)
↓
PLAYING(B)
```

语言学习场景默认不采用：

```text
A 播完
↓
再播放 B
```

因为用户点击 B 的语义通常是：

```text
现在播放 B
```

而不是：

```text
稍后排队播放 B
```

所以默认策略是：

```text
REPLACE_CURRENT
```

---

# 13. Play Button Controller

业务入口统一为：

```java
void onPlayButtonClicked(Source source) {

    PlaybackJob current = currentJob;

    if (current == null) {
        start(source);
        return;
    }

    if (current.sourceId.equals(source.id)) {
        stop();
        return;
    }

    replace(source);
}
```

UI 不负责 Azure cancel、AudioTrack stop 等具体操作。

---

# 14. Replace 必须是一等操作

不要把 Replace 仅理解成：

```java
stop(A);
play(B);
```

推荐提供：

```java
replaceWith(B);
```

因为 Replace 的语义是：

```text
B 成为唯一有效 currentJob
A 立即失效
```

一个完整的 Replace：

```text
PLAYING(A)
↓
generation++
↓
invalidate A
↓
clear A buffer
↓
stop AudioPlayer
↓
request Azure cancel A
↓
create B
↓
LOADING(B)
```

注意：

不要等待 Azure A 完成 cancel 后再启动 B。

错误：

```text
await cancel(A)
↓
await player.stop()
↓
start(B)
```

推荐：

```text
invalidate(A)
↓
player.stopImmediately()
↓
start(B)

A 的后台 cleanup 自行结束
```

---

# 15. Generation ID

这是防止播放残留和异步竞态的核心机制。

每次产生新的有效播放意图：

```java
long generation = ++currentGeneration;
```

例如：

```text
A generation = 10
B generation = 11
```

所有 Azure / Audio 异步 callback 必须验证：

```java
if (generation != currentGeneration) {
    return;
}
```

---

# 16. 为什么不能只依赖 cancel

场景：

```text
t=0
点击 A

t=100ms
Azure 开始处理 A

t=200ms
点击 B

t=250ms
B 开始

t=400ms
A callback 才到达
```

即使已经调用：

```text
cancel(A)
```

也不能假设旧 callback 一定不会发生。

因此：

```text
cancel
=
资源优化
```

而：

```text
generation / jobId validation
=
正确性保障
```

这两个概念必须严格区分。

---

# 17. Stop

Stop 的完整语义：

```text
generation++
↓
invalidate currentJob
↓
request Azure cancel
↓
AudioPlayer.stopImmediately()
↓
AudioBufferQueue.clear()
↓
release currentJob
↓
IDLE
```

伪代码：

```java
void stop() {

    ++currentGeneration;

    PlaybackJob oldJob = currentJob;
    currentJob = null;

    tts.cancel(oldJob);

    audioPlayer.stop();
    audioPlayer.clear();

    setState(IDLE);
}
```

旧 callback：

```java
if (job.generation != currentGeneration) {
    return;
}
```

因此即使 callback 晚到，也不会重新播放旧音频。

---

# 18. 快速连续点击

必须支持：

```text
A
20 ms
B
20 ms
C
20 ms
A
```

最终结果：

```text
最后一个 A 有效
```

前面任务全部：

```text
STALE
```

采用：

```text
latest wins
```

策略。

不要为普通按钮播放建立 FIFO 队列。

---

# 19. PlaybackController 并发模型

`PlaybackController` 必须是整个播放系统唯一的状态拥有者。

禁止：

```text
UI Thread
Azure Callback Thread
Audio Thread
```

同时直接修改：

```text
currentState
currentJob
```

推荐：

```text
UI Event ────────┐
Azure Event ─────┼──> Playback Event Queue
Audio Event ─────┘
                         ↓
                 PlaybackController
                         ↓
                      reducer
                         ↓
                       State
```

可使用：

- Coroutine Actor
- 单线程 Dispatcher
- Serial Executor
- Event Loop
- Mutex + 严格单入口

推荐 Actor / Serial Executor 模式。

---

# 20. PlaybackEvent

所有异步行为转化成事件：

```text
PlaybackEvent
- PlayRequested
- StopRequested
- ReplaceRequested
- AudioChunkReceived
- AudioReady
- PlaybackStarted
- SynthesisCompleted
- PlaybackCompleted
- CancelCompleted
- Failed
```

状态只能由 reducer / controller 更新。

禁止 callback 直接修改 UI 状态。

---

# 21. PLAYING 状态的定义

不要在：

```text
Azure request sent
```

时设置：

```text
PLAYING
```

也不要在：

```text
第一个 chunk received
```

时立即设置 `PLAYING`。

推荐在：

```text
AudioPlayer 真正开始消费音频
```

时触发：

```text
PlaybackStarted(jobId)
```

然后：

```text
LOADING
↓
PLAYING
```

这样 UI 与真实声音更同步。

---

# 22. PlaybackCompleted

播放完成必须由：

```text
AudioPlayer buffer drained
```

触发。

禁止：

```text
SynthesisCompleted
↓
state = IDLE
```

正确：

```text
Azure SynthesisCompleted
↓
mark inputEnded = true

AudioPlayer queue empty
+
inputEnded == true
↓
PlaybackCompleted
↓
IDLE
```

---

# 23. UI State

播放列表或多个单词按钮不得各自持有独立：

```java
boolean isPlaying;
```

否则可能出现多个按钮同时显示 Playing。

正确方式：

```text
PlaybackController
↓
Global PlaybackState
```

每个按钮根据：

```text
currentSourceId == mySourceId
```

计算自身状态。

例如：

```text
currentSource = territory
state = PLAYING
```

UI：

```text
territory  ■
terrain    ▶
terrace    ▶
```

点击 `terrain`：

```text
territory  ▶
terrain    loading
terrace    ▶
```

随后：

```text
territory  ▶
terrain    ■
terrace    ▶
```

---

# 24. UI 映射

推荐：

```text
IDLE
→ Play icon

LOADING
→ Spinner / Loading

PLAYING
→ Stop / Speaker animation

ERROR
→ Play icon + Error feedback
```

短 TTS 场景通常无需 Pause。

如果未来存在：

- 长文章
- Podcast
- AI 长回答

再扩展：

```text
PAUSED
```

不要为几秒钟的单词播放提前增加复杂度。

---

# 25. STT 架构

推荐：

```text
AudioRecord
↓
AudioProcessor
↓
PCM 16k / 16-bit / mono
↓
PushAudioInputStream
↓
Azure SpeechRecognizer
```

这样录音数据可以同时用于：

```text
PCM
├── Azure STT
├── waveform
├── VAD
├── local recording
├── pronunciation assessment
└── other STT provider
```

避免 Azure SDK 完全接管麦克风。

---

# 26. STT Session

对于：

```text
按一次
说一句
结束
```

可使用单次识别。

对于：

```text
开始口语练习
↓
持续讲话
↓
实时字幕
```

应使用连续识别。

Session 生命周期：

```text
IDLE
↓
STARTING
↓
LISTENING
↓
STOPPING
↓
IDLE
```

---

# 27. Recognizing / Recognized

必须区分：

```text
Recognizing
=
partial hypothesis
```

```text
Recognized
=
final result
```

UI：

```text
confirmedText + partialText
```

数据库只保存：

```text
Recognized
```

否则容易得到：

```text
I
I want
I want to
I want to go
```

全部被重复存储。

---

# 28. TTS + STT 互斥策略

第一阶段推荐：

```text
Half Duplex / Interrupt
```

不要直接实现 Full Duplex。

推荐规则：

```text
TTS SPEAKING
↓
用户开始录音
↓
Stop TTS
↓
clear playback
↓
Start STT
```

如果未来需要：

```text
AI 正在讲话
用户直接打断
```

则增加：

```text
VAD
↓
detect user speech
↓
invalidate TTS
↓
stop playback
↓
start / continue STT
```

Full Duplex 会额外涉及：

```text
AEC
Acoustic Echo Cancellation
```

以及：

- Speaker → Mic 回声
- 音频路由
- 延迟同步
- 设备差异

复杂度显著提高，不建议作为第一阶段目标。

---

# 29. Android Audio Focus

Android 播放必须统一管理 Audio Focus。

基本生命周期：

```text
requestAudioFocus
↓
play
↓
abandonAudioFocus
```

处理：

```text
AUDIOFOCUS_LOSS
AUDIOFOCUS_LOSS_TRANSIENT
AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
AUDIOFOCUS_GAIN
```

Audio Focus 事件同样应转换成：

```text
PlaybackEvent
```

进入 PlaybackController。

业务层不得直接监听后自行修改播放状态。

---

# 30. Android AudioTrack

推荐：

```text
AudioTrack
MODE_STREAM
24 kHz
PCM 16-bit
Mono
```

播放器作为长生命周期对象复用。

不推荐：

```text
每个单词
↓
new AudioTrack
↓
play
↓
release
```

AudioTrack 建议由：

```text
AudioPlayer
```

统一持有。

---

# 31. Audio Underrun

必须监控：

```text
buffer underrun
```

出现以下问题时优先检查：

- 开头吞音
- 中间断裂
- 偶发爆音
- 网络正常但声音卡顿

日志建议记录：

```text
prebufferMs
bufferSize
underrunCount
writeLatency
queueDepth
```

---

# 32. 错误处理

错误分层：

```text
SpeechError
├── Network
├── Authentication
├── Service
├── Cancellation
├── Timeout
└── Unknown
```

```text
AudioError
├── AudioFocus
├── OutputInit
├── OutputWrite
├── InputInit
├── InputRead
├── DeviceChanged
└── Unknown
```

```text
PlaybackError
├── TtsError
├── AudioError
└── InternalStateError
```

UI 只处理：

```text
PlaybackError
```

不要暴露 Azure SDK 具体错误对象。

---

# 33. 日志与可观测性

每次 TTS 至少记录：

```text
jobId
sourceId
generation
azureRequestId

textLength
voice
audioFormat

requestTimestamp
firstAudioTimestamp
playbackStartTimestamp
synthesisCompletedTimestamp
playbackCompletedTimestamp

firstByteLatency
playStartLatency
totalSynthesisLatency
totalPlaybackDuration

prebufferMs
underrunCount

cancelReason
errorCode
```

这样出现：

```text
播放很慢
```

可以快速区分：

```text
Azure 服务慢
网络慢
首包慢
Prebuffer 太大
AudioTrack underrun
UI 状态错误
```

---

# 34. 推荐状态机

对 UI 暴露的最终状态机：

```text
                 click A
       ┌───────────────────────┐
       │                       ▼
     IDLE                  LOADING(A)
                              │
                       playback started
                              │
                              ▼
                          PLAYING(A)
                           │       │
                   finished│       │click A
                           │       ▼
                           │      IDLE
                           │
                           ▼
                          IDLE
```

切换 Source：

```text
PLAYING(A)
+
click B
↓
invalidate A
↓
LOADING(B)
↓
PLAYING(B)
```

Loading 切换：

```text
LOADING(A)
+
click B
↓
invalidate A
↓
LOADING(B)
```

核心特点：

```text
不存在等待旧任务完全结束后才能开始新任务
```

---

# 35. 推荐 Reducer 逻辑

概念伪代码：

```java
onEvent(event) {

    switch (event) {

        case PlayRequested(source):

            if (currentJob == null) {
                start(source);
                return;
            }

            if (currentJob.sourceId.equals(source.id)) {
                stopCurrent();
                return;
            }

            replaceCurrent(source);
            return;


        case AudioChunkReceived(job, chunk):

            if (!isCurrent(job)) {
                return;
            }

            audioPlayer.enqueue(chunk);
            return;


        case PlaybackStarted(job):

            if (!isCurrent(job)) {
                return;
            }

            setState(PLAYING(job.sourceId));
            return;


        case SynthesisCompleted(job):

            if (!isCurrent(job)) {
                return;
            }

            audioPlayer.markInputEnded();
            return;


        case PlaybackCompleted(job):

            if (!isCurrent(job)) {
                return;
            }

            currentJob = null;
            setState(IDLE);
            return;


        case Failed(job, error):

            if (!isCurrent(job)) {
                return;
            }

            currentJob = null;
            audioPlayer.stop();
            setState(ERROR(error));
            return;
    }
}
```

核心方法：

```java
boolean isCurrent(PlaybackJob job) {
    return currentJob != null
        && currentJob.jobId.equals(job.jobId)
        && currentJob.generation == currentGeneration;
}
```

---

# 36. 推荐首版实现范围

第一阶段只实现：

```text
TTS:
- Azure PCM streaming
- AudioTrack streaming
- prebuffer
- stop
- replace current
- generation validation
- Audio Focus
- playback metrics
```

```text
STT:
- AudioRecord
- 16k PCM
- PushAudioInputStream
- continuous recognition
- partial/final transcript
```

状态机：

```text
IDLE
LOADING
PLAYING
ERROR
```

交互策略：

```text
same source
→ stop

different source
→ replace

rapid clicks
→ latest wins
```

暂不实现：

- TTS FIFO queue
- Full Duplex
- AEC
- Pause
- 多播放器并行
- 多个 SpeechSynthesizer 并行工作

除非业务场景明确需要。

---

# 37. 不变量

整个系统必须始终满足以下不变量。

## 37.1 单一 Current Job

任意时刻：

```text
currentJob <= 1
```

## 37.2 单一音频输出 Owner

任意时刻只能有一个模块拥有：

```text
AudioPlayer
```

播放权限。

## 37.3 Latest Wins

用户产生新的播放意图后：

```text
之前所有 Job 均不得重新影响播放
```

## 37.4 Callback Validation

任何异步 callback 在执行副作用之前必须验证：

```text
jobId
+
generation
```

## 37.5 Synthesis 与 Playback 分离

永远不得认为：

```text
SynthesisCompleted == PlaybackCompleted
```

## 37.6 UI 不拥有播放真相

播放状态唯一 Source of Truth：

```text
PlaybackController
```

---

# 38. 最终推荐架构

```text
                        UI
                         │
                         ▼
                PlaybackController
                 │               │
          PlaybackState      PlaybackJob
                 │
        ┌────────┴──────────┐
        │                   │
        ▼                   ▼
     TtsClient          AudioPlayer
        │                   │
        ▼                   ▼
 Azure Speech SDK        AudioTrack
        │
        ▼
      PCM Stream
        │
        └───────────────→ AudioBufferQueue
```

STT：

```text
Microphone
    ↓
AudioRecord
    ↓
AudioProcessor
    ↓
16k PCM
    ↓
PushAudioInputStream
    ↓
Azure SpeechRecognizer
    ↓
Recognizing / Recognized
    ↓
RecognitionSession
    ↓
UI
```

---

# 39. 最重要的实现原则

如果只能保留最关键的规则，则必须保证：

1. **Azure Speech SDK 不直接承担复杂 App 的最终音频播放控制。**
2. **TTS / STT 使用固定、明确的 Audio Contract。**
3. **全局只有一个 PlaybackController 和一个 current playback job。**
4. **同一按钮再次点击 = Stop。**
5. **点击其他按钮 = Replace Current。**
6. **快速点击采用 Latest Wins。**
7. **每次新播放意图都递增 generation。**
8. **所有异步 callback 必须校验 jobId / generation。**
9. **Cancel 只负责资源释放，generation 才负责正确性。**
10. **SynthesisCompleted 与 PlaybackCompleted 必须严格分开。**
11. **播放状态只由 PlaybackController 维护。**
12. **AudioPlayer / AudioRecorder 生命周期统一管理，禁止业务层直接操作底层音频对象。**

遵守以上约束后，大部分常见问题：

```text
停止后又突然播放
两个按钮同时显示播放
A 没结束 B 又混进来
快速点击后播放错误内容
开头吞音
中途断音
Azure 合成结束但 UI 提前恢复
蓝牙 / Audio Focus 后状态错乱
```

都可以从架构层显著降低发生概率。

---

---

# 40. 本仓库的落点与取舍

本节记录这份设计在代码里对应到哪里，以及哪些部分明确没做。

## 40.1 模块对应

| 设计 | 实现 |
| --- | --- |
| `PlaybackController` / `PlaybackState` / `PlaybackJob` / `PlaybackEvent` | `core/speech/PlaybackController.kt`、`core/speech/Playback.kt` |
| `TtsClient` | `domain/speaking/SpeechProvider`（领域接口）+ `core/speech/AzureSpeechProvider`（Azure 实现） |
| `AudioPlayer` / `AudioBufferQueue` / `AudioFocusManager` | `core/speech/PcmAudioPlayer.kt` |
| 播放按钮的 UI 映射（§24） | `core/designsystem/SpeakButton.kt` |
| 应用级单一入口 | `core/speech/SpeechController.kt` |

`§3` 那张包结构图没有照搬。`tts/`、`playback/`、`audio/` 三层在这个体量下只有一个实现，
拆成三个包只是多几层目录，所以统一放在 `core/speech`，靠文件划分职责
（`ARCHITECTURE.md` §0.3、§0.4：不为"以后可能需要"另起一套平行结构）。

## 40.2 与设计有出入的地方

- **Prebuffer 的做法不同，而且不能按 §7 改**（§7）。这里不是攒够 80~150 ms PCM 再开声：
  `begin()` 期间**完全不出声**，第一块语音到手时才把前置静音垫进缓冲再 `play()`。
  这不是风格差异——对着空缓冲 `play()` 会被 AudioFlinger 判 underrun 直接停掉 track，
  表现就是"音频全写进去了，一个字没响"。规则见 `DECISIONS.md` D-039 和
  `ARCHITECTURE.md`「朗读播放的不变量」，**改 `PcmAudioPlayer` 前先读那五条**。
- **Replace 时仍然等了一次 Azure 停止**（§14）。全程复用同一个 `SpeechSynthesizer`（§8），
  一个实例不能同时合成两段，provider 用 `speakMutex` 串行化。掐声音、失效旧 job、
  界面切到 LOADING 都是立刻发生的，等待只落在"新合成什么时候发出去"这一段。
- **`PlaybackError` 没有按来源分型**（§32）。Azure 错误和音频设备错误都只给一句中文，
  界面处理方式也只有一种，所以先是一个 `message`，等真有分别处理的需要再拆。
- **`PAUSED` 没有实现**（§24），短句朗读不需要。
- **§33 的日志没有另起一套**。provider 那边已经按 D-039 记了请求编号、首块音频耗时、
  收到 vs 写入时长、总耗时；控制器只补 job 级的一行（jobId / sourceId / generation /
  字数 / 起播耗时 / 总耗时 / 结果）。underrun 计数没有单独加读取——那个文件的规则是
  "靠日志改，不靠推理改"，现有日志已经能区分"合成慢"和"播放丢音"。

## 40.3 这一版没做的

- **STT 仍然由 Azure SDK 接管麦克风**（§25、§26、§36）。连续识别和 partial/final
  已经有了（`transcribeContinuously`），但走的是 `AudioConfig.fromDefaultMicrophoneInput()`，
  不是 `AudioRecord` → 重采样 → `PushAudioInputStream`。自建录音链路是为波形、VAD、
  本地录音留存、换 provider 服务的，这些还没有需求方；先按 §28 做到"录音前掐掉朗读"的半双工。
- **Full Duplex / AEC / barge-in**（§28）、**TTS 队列**、**多播放器并行**（§36）都没有做，
  也不打算在 MVP 里做。

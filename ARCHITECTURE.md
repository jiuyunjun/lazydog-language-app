# 技术架构

## 1. 目标与约束

- Android 原生 Kotlin 应用。
- 无自建服务端，数据本地优先。
- 在线依赖包括 AI 服务和 Azure Speech。
- UI 优先 Jetpack Compose + Material 3。
- 网络不可用时，已有知识库、历史和可缓存任务仍应可查看。
- 架构需要允许以后替换 AI 提供商、复习算法和阅读来源。

## 2. 推荐技术栈

- Kotlin
- Jetpack Compose / Material 3
- Navigation Compose
- ViewModel + Kotlin Coroutines + StateFlow
- Room
- DataStore
- WorkManager
- Retrofit/OkHttp 或提供商官方兼容客户端
- Azure Speech SDK
- Kotlin Serialization
- Hilt（仅在依赖关系复杂到值得引入时）

具体依赖版本在创建工程时以当时的稳定官方版本为准，不在立项文档中锁死。

## 3. 分层

```text
Compose UI
    ↓
ViewModel / UI State
    ↓
Use Cases
    ↓
Repositories
    ├── Room / DataStore
    ├── AI Provider
    ├── Speech Provider
    └── Reading Sources
```

UI 不直接调用网络 SDK 或拼装 AI 提示词。领域层不依赖具体 Compose 页面。

首版建议保持单个 App 模块并按功能分包；只有在构建时间或边界确有需要时再拆 Gradle 多模块。

## 4. 推荐包结构

```text
app/
  core/
    database/
    network/
    model/
    designsystem/
  feature/
    onboarding/
    assessment/
    today/
    vocabulary/
    grammar/
    reading/
    speaking/
    quiz/
    library/
    settings/
  domain/
    scheduling/
    generation/
    validation/
```

## 5. 核心数据模型

### LearnerProfile

- 当前 CEFR 估计与置信度
- 词汇能力区间
- 语法领域能力
- 阅读和朗读能力摘要
- 学习目标、兴趣和每日时长
- 最近一次画像更新时间

### KnowledgeItem

统一身份和调度字段：

- `id`
- `type`: vocabulary / grammar
- `stage`: unseen / exposed / learning / familiar / mastered
- `difficulty`
- `stability`
- `lastReviewedAt`
- `nextReviewAt`
- `lapseCount`
- `createdAt` / `updatedAt`

单词和语法的详细内容分别存储，不强迫使用同一张超宽表。

### LearningSession

- 状态：planned / active / paused / completed / abandoned
- 计划步骤及当前步骤
- 预计与实际时长
- 本次新知识和复习知识 ID
- 创建、暂停和完成时间

### LearningEvent

使用追加式事件记录每次学习行为：

- 知识项 ID
- 来源：卡片、阅读、朗读、测试
- 题型或活动类型
- 结果与用户四档反馈
- 响应时间
- 时间戳

聚合后的掌握状态可以重算，不能只保存一个无法解释的最终分数。

### ReadingMaterial

- 标题、正文、难度和来源
- 目标知识、新知识与检测到的其他生词
- 生成参数和模型标识
- 校验结果
- 创建时间

## 6. 复习调度

定义可替换接口，例如：

```kotlin
interface ReviewScheduler {
    fun schedule(previous: MemoryState, rating: ReviewRating, at: Instant): MemoryState
}
```

首版可以实现简化间隔算法，但字段设计需要支持以后迁移到 FSRS。每日计划优先级：

1. 已到期且多次遗忘的项目
2. 普通到期项目
3. 最近新学、需要巩固的项目
4. 在剩余时间内加入少量新知识

## 7. 阅读生成管线

```text
读取用户画像与时间预算
  → 选择复习项和新知识
  → 组装结构化生成请求
  → 调用 AI
  → 解析 JSON
  → 本地分析正文
  → 验证目标词、篇幅和生词比例
  → 合格后保存，否则修复或重试
```

内容来源使用统一接口：

```kotlin
interface ReadingSource {
    suspend fun getContent(request: ReadingRequest): ReadingContent
}
```

计划实现顺序：AI 生成、粘贴文本、URL 提取、Web 搜索。

## 8. 服务和密钥

- 密钥由用户在设置页输入并保存在应用私有存储中。
- 禁止写入源码、资源文件、日志、崩溃报告或 Git。
- 对个人项目而言接受客户端持有密钥的风险，但界面必须明确说明 APK 无法彻底保护密钥。
- AI 与 Speech 各自通过 provider 接口封装，领域层不依赖具体 SDK。
- 网络请求应设置超时、有限重试和可取消机制。

## 9. 数据备份

无账户服务时，长期学习记录是最重要资产。MVP 后期至少提供一种用户主动触发的本地导出/导入方式。导出格式必须带 schema 版本，并避免默认包含密钥与录音。

## 10. 测试策略

- 单元测试：调度算法、能力估计、阅读校验、学习计划生成。
- Repository 测试：Room 映射、事务和迁移。
- 契约测试：AI 合法、缺字段、错误类型和超长返回。
- UI 测试：首次测试、完整今日流程、中断恢复和网络失败。
- 人工验证：Azure Speech 权限、录音、音频焦点和真实设备行为。

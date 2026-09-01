package com.lazydog.english.core.model

/**
 * 学习目标在偏好里存成一个字符串（`learning_goal`），页面上是多选。
 *
 * 没有为了多选去改存储格式：这个值最终只有一个去处——进提示词，让 AI 知道这个人为什么学。
 * "日常口语、工作邮件"本来就是一句能直接读的话，拆成 Set 存再拼回去只是多绕一圈。
 */
object LearningGoals {

    private const val SEPARATOR = "、"

    fun join(goals: Collection<String>): String =
        goals.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(SEPARATOR)

    /** 老数据里是单个目标，split 后就是只有一项的集合，不需要迁移。 */
    fun split(raw: String): Set<String> =
        raw.split(SEPARATOR, ",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

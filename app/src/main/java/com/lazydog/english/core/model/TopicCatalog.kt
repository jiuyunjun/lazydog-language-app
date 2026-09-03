package com.lazydog.english.core.model

import kotlin.random.Random

/**
 * 可选话题的公共清单。
 *
 * 阅读起始页原来只摆用户在引导页勾过的前四个兴趣——引导页本身也只给九个预置项，
 * 于是"挑个主题"实际上是在三四个词里挑。话题不该是引导页的副产品：
 * 今天想读什么和当初勾了什么兴趣是两件事，起始页得自己有一片够挑的清单。
 *
 * [starter] 是引导页和设置页那份短清单（勾兴趣是个一次性的粗筛，摆四十个反而没人勾）；
 * [all] 是起始页摆开的全集，用户勾过的兴趣排在最前面（[withPreferred]）。
 */
object TopicCatalog {

    /** 引导页/设置页勾兴趣用的短清单。 */
    val starter = listOf("旅行", "美食", "职场", "健康", "音乐", "历史", "游戏", "科技", "电影")

    /** 起始页摆开的全集，按大致的领域分组排列，方便扫。 */
    val all = listOf(
        // 日常
        "旅行", "美食", "咖啡", "健身", "健康", "睡眠", "宠物", "家居", "穿搭", "省钱",
        // 工作
        "职场", "面试", "远程办公", "创业", "理财", "谈判", "跳槽", "副业",
        // 科技
        "科技", "人工智能", "手机数码", "编程", "太空", "汽车", "机器人",
        // 人文
        "历史", "心理学", "哲学", "语言", "教育", "社会新闻", "环保", "城市",
        // 娱乐
        "电影", "剧集", "音乐", "游戏", "体育", "篮球", "足球", "动漫", "摄影", "读书",
        // 生活里的故事
        "校园", "友情", "恋爱", "家庭", "搬家", "求医", "露营", "搞砸的一天",
    )

    /** 用户勾过的兴趣排在前面，其余按 [all] 的顺序补齐，不重复。 */
    fun withPreferred(preferred: Collection<String>): List<String> {
        val head = preferred.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return head + all.filterNot { it in head }
    }

    /**
     * 随机挑一个，可以排除当前已选的那个——点"随机"却原地不动，看起来就像按钮坏了。
     */
    fun random(exclude: String = "", random: Random = Random.Default): String {
        val pool = all.filterNot { it == exclude.trim() }
        return pool[random.nextInt(pool.size)]
    }
}

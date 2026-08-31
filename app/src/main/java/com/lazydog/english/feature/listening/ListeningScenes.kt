package com.lazydog.english.feature.listening

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 听力首页的场景卡片（英语听力训练模块DESIGN.md §14）。
 *
 * [subScenes] 是文档 §8 的二级分类，直接进生成提示词——只给一级场景的话，
 * 十句话很容易全在说同一件事。
 */
internal data class ListeningScene(
    val nameZh: String,
    val noteZh: String,
    val icon: ImageVector,
    val subScenes: List<String>,
)

internal val listeningScenes = listOf(
    ListeningScene(
        "日常高频", "每天都会遇到", Icons.Outlined.WbSunny,
        listOf("打招呼", "约时间", "拜托别人", "婉拒", "抱怨天气", "买单", "路上闲聊", "临时改计划"),
    ),
    ListeningScene(
        "商务职场", "会议 · 汇报 · 客户", Icons.Outlined.Business,
        listOf("项目进度", "会议", "汇报", "提需求", "拒绝需求", "请求帮助", "Deadline", "客户沟通", "道歉", "风险说明"),
    ),
    ListeningScene(
        "恋爱关系", "约会 · 关心 · 吵架", Icons.Outlined.Favorite,
        listOf("约会", "暧昧", "关心", "撒娇", "吵架", "道歉", "和好", "表达不满", "提出边界", "日常聊天"),
    ),
    ListeningScene(
        "影视场景", "经典场景的说法", Icons.Outlined.Movie,
        listOf("大战前争执", "临别道别", "揭穿谎言", "下决心", "审讯逼问", "重逢", "临终托付"),
    ),
    ListeningScene(
        "游戏世界", "组队 · 指令 · 剧情", Icons.Outlined.SportsEsports,
        listOf("报点", "掩护", "撤退", "组队", "战术", "装备", "Boss 战", "任务", "剧情对白", "队友吐槽"),
    ),
    ListeningScene(
        "旅行", "机场 · 酒店 · 餐厅", Icons.Outlined.Flight,
        listOf("值机", "过安检", "转机延误", "酒店入住", "房间有问题", "问路", "点餐", "退换与投诉"),
    ),
    ListeningScene(
        "科技 IT", "Bug · 开发 · 项目", Icons.Outlined.Code,
        listOf("说明 Bug", "评审代码", "排期", "线上事故", "技术选型分歧", "站会同步", "求助同事"),
    ),
    ListeningScene(
        "朋友社交", "聊天 · 玩笑 · 聚会", Icons.AutoMirrored.Outlined.Chat,
        listOf("约饭", "吐槽", "开玩笑", "八卦", "安慰", "劝酒与拒绝", "分摊费用", "临时放鸽子"),
    ),
    ListeningScene(
        "生存英语", "紧急情况 · 求助", Icons.Outlined.Warning,
        listOf("看病描述症状", "报警求助", "东西丢了", "迷路", "车坏了", "过敏与忌口", "银行卡出问题"),
    ),
)

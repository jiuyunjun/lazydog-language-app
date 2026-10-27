package com.lazydog.english

import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.lazydog.english.core.ask.AskPagePolicy
import com.lazydog.english.feature.ask.AskHost

/** 唯一页面注册入口。提问宿主按 back stack entry 隔离，业务页不再手动包装。 */
fun NavGraphBuilder.appScreen(
    route: String,
    ask: AskPagePolicy,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        when (ask) {
            AskPagePolicy.Required -> AskHost(pageName = route) { content(entry) }
            is AskPagePolicy.Disabled -> content(entry)
        }
    }
}

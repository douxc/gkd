package li.gkd.app.ui.home

import li.gkd.app.META
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import li.gkd.app.ui.component.gkTopAppBarScroll
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.app.AppInfoRepository
import li.gkd.app.network.AppLinks
import li.gkd.app.resources.*
import li.gkd.app.rule.RuleGroupSnapshot
import li.gkd.app.rule.ruleGroupState
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.state.Loadable
import li.gkd.app.subscription.SubscriptionRepository
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkGroupNameText
import li.gkd.app.ui.component.GkRuleStats
import li.gkd.app.ui.component.GkRuleStatsData
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkPermissionRestrictionDialog
import li.gkd.app.ui.component.GkSwitch
import li.gkd.app.ui.component.GkTooltipIconButtonBox
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.rememberColumnScrollState
import li.gkd.app.ui.component.textSize
import li.gkd.app.ui.icon.GkAnimatedRocketIcon
import li.gkd.app.ui.navigation.ActionLogRoute
import li.gkd.app.ui.navigation.ActivityLogRoute
import li.gkd.app.ui.navigation.AppConfigRoute
import li.gkd.app.ui.navigation.PrivilegeServiceRoute
import li.gkd.app.ui.navigation.WorkModeRoute
import li.gkd.app.settings.AutomatorMode
import li.gkd.app.model.findOption
import li.gkd.app.ui.platform.UiHost
import li.gkd.app.ui.platform.setStatusServiceEnabled
import li.gkd.app.ui.platform.switchAutomator
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.db.RuleGroupType
import org.jetbrains.compose.resources.stringResource

@Composable
fun dashboardSummary(state: Loadable<RuleGroupSnapshot>, actionCount: Long): String =
    when (state) {
        Loadable.Loading -> ""
        is Loadable.Failure -> stringResource(Res.string.data_load_failed)
        is Loadable.Ready -> {
            val groups = state.value.groups
            val rules =
                listOfNotNull(
                        if (groups.globalGroups.isNotEmpty())
                            stringResource(
                                Res.string.subscription_global_count,
                                groups.globalGroups.size.toString(),
                            )
                        else null,
                        if (groups.appGroupSize > 0)
                            stringResource(
                                Res.string.subscription_app_rule_counts,
                                groups.appSize.toString(),
                                groups.appGroupSize.toString(),
                            )
                        else null,
                    )
                    .joinToString("/")
                    .ifEmpty { stringResource(Res.string.rules_empty) }
            if (actionCount > 0)
                stringResource(
                    Res.string.action_count_summary,
                    rules,
                    actionCount.toString(),
                )
            else rules
        }
    }

@Composable
fun dashboardPage(host: UiHost, vm: HomeViewModel): ScaffoldExt {
    val platform = dashboardPlatformState()
    val store by SettingsRepository.settings.collectAsStateWithLifecycle()
    return ScaffoldExt(
        BottomNavItem.Dashboard,
        topBar = { GkTopAppBar(title = { Text(META.appName) }) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            li.gkd.app.ui.component.GkSettingItem(
                title = stringResource(Res.string.a11y_label),
                subtitle = stringResource(if (platform.a11yRunning) Res.string.a11y_running else Res.string.a11y_stopped),
                onClick = { li.gkd.app.ui.platform.openA11ySettings() },
            )
            li.gkd.app.ui.component.GkTextSwitch(
                title = stringResource(Res.string.rule_matching_label),
                checked = store.enableMatch,
                onCheckedChange = { vm.toggleMatching() },
            )
            GkPageBottomSpace()
        }
    }
}

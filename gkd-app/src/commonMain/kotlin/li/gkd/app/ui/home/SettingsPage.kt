package li.gkd.app.ui.home

import li.gkd.app.META
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import li.gkd.app.ui.component.gkTopAppBarScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.notif.formatNotificationTemplate
import li.gkd.app.resources.*
import li.gkd.app.rule.ruleGroupState
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkTextMenu
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.rememberColumnScrollState
import li.gkd.app.ui.navigation.AboutRoute
import li.gkd.app.ui.navigation.ActionToastRoute
import li.gkd.app.ui.navigation.AdvancedPageRoute
import li.gkd.app.ui.navigation.BlockA11yAppListRoute
import li.gkd.app.ui.navigation.BlockA11ySetupRoute
import li.gkd.app.ui.navigation.NotificationTextRoute
import li.gkd.app.ui.option.DarkThemeOption
import li.gkd.app.model.findOption
import li.gkd.app.ui.page.BackupDialog
import li.gkd.app.ui.page.GkBackupDialogs
import li.gkd.app.ui.platform.UiHost
import li.gkd.app.ui.platform.dynamicColorAvailable
import li.gkd.app.ui.platform.privilegeAvailable
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.style.titleItemPadding
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun settingsPage(host: UiHost, vm: HomeViewModel): ScaffoldExt {
    val store by SettingsRepository.settings.collectAsStateWithLifecycle()
    return ScaffoldExt(
        BottomNavItem.Settings,
        topBar = { GkTopAppBar(title = { Text(BottomNavItem.Settings.label) }) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            GkTextMenu(
                title = stringResource(Res.string.theme_mode),
                option = DarkThemeOption.objects.findOption(store.enableDarkTheme),
                onOptionChange = { option -> SettingsRepository.updateSettings { it.copy(enableDarkTheme = option.value) } },
            )
            GkSettingItem(
                title = stringResource(Res.string.a11y_label),
                onClick = { li.gkd.app.ui.platform.openA11ySettings() },
            )
            GkPageBottomSpace()
        }
    }
}

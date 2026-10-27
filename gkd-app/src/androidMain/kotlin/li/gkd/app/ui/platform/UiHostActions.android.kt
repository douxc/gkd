package li.gkd.app.ui.platform

import li.gkd.app.platform.service.ServiceController
import li.gkd.app.ui.androidState
import li.gkd.app.settings.AutomatorMode
import li.gkd.app.ui.share.launchUi

actual fun UiHost.setStatusServiceEnabled(enabled: Boolean) {
    // Legacy shared routes cannot start a foreground notification service.
}

actual fun UiHost.changeAutomatorMode(mode: AutomatorMode) {
    mainVm.androidState.updateAutomatorMode(mode)
}

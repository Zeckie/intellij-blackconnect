package me.lensvol.blackconnect.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.notification.impl.NotificationFullContent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import me.lensvol.blackconnect.config.BlackConnectConfigurable
import me.lensvol.blackconnect.settings.BlackConnectGlobalSettings

const val REMINDER_TEXT = """We've added several new features for your convenience:<br><br>
–&nbsp;Reformat on file save.<br>
–&nbsp;Targeting different Python versions.<br>
–&nbsp;Jupyter Notebook support.<br>
–&nbsp;Option to hide "syntax error" balloons.<br>
<br>
You can enable/disable them individually in the project settings.
"""

class NewSettingsReminderNotification(
        notificationGroup: NotificationGroup,
        project: Project,
        configuration: BlackConnectGlobalSettings
) : Notification(
            notificationGroup.displayId,
            "BlackConnect",
    REMINDER_TEXT,
            NotificationType.INFORMATION,
            NotificationListener.URL_OPENING_LISTENER
), NotificationFullContent {
    init {
        icon = IconLoader.getIcon("/icons/blackconnect.svg")

        addAction(NotificationAction.createSimpleExpiring("Show settings") {
            configuration.showSaveTriggerOptIn = false
            ShowSettingsUtil.getInstance().editConfigurable(project,
                BlackConnectConfigurable(project)
            )
        })
        addAction(NotificationAction.createSimpleExpiring("Not now") {})
        addAction(NotificationAction.createSimpleExpiring("Do not display again") {
            configuration.showSaveTriggerOptIn = false
        })
    }
}
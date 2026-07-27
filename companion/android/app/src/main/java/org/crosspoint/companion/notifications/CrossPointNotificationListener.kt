package org.crosspoint.companion.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CrossPointNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        if (!NotificationRepository.enabled(this)) return
        NotificationRepository.clear(this)
        activeNotifications.orEmpty().forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (!NotificationRepository.enabled(this)) return
        if (notification.packageName == packageName) return
        if (notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appName = runCatching {
            val info = packageManager.getApplicationInfo(notification.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(notification.packageName)

        NotificationRepository.upsert(
            this,
            PhoneNotification(
                key = notification.key,
                app = appName.take(64),
                title = title.take(240),
                text = text.take(600),
                timestamp = notification.postTime,
            )
        )
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        NotificationRepository.remove(this, notification.key)
    }
}

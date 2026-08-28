package si.sopotnik

import android.service.notification.NotificationListenerService

/**
 * V fazi 1 služi le kot ključ za MediaSessionManager.getActiveSessions
 * (nadzor glasbe); branje obvestil pride v fazi 2.
 */
class NotifListener : NotificationListenerService()

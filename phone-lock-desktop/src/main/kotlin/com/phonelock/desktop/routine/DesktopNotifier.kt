package com.phonelock.desktop.routine

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

/**
 * 루틴 알림(52차)을 데스크탑 트레이 풍선 알림으로 띄우기 위한 다리 역할. Main.kt의 startApp()이
 * 이미 Tray(...) composable을 쓰고 있어서(트레이 아이콘 자체가 이미 있음), 별도 java.awt.TrayIcon을
 * 새로 추가하면 트레이에 아이콘이 두 개로 보이는 문제가 생긴다 — 대신 Compose Desktop의
 * TrayState.sendNotification()이 기존 트레이 아이콘 그대로 풍선 알림을 띄워주므로, Main.kt에서 만든
 * TrayState 인스턴스를 여기 등록해두고 Composable이 아닌 곳(RoutineNotifier)에서 재사용한다.
 */
object DesktopNotifier {
    var trayState: TrayState? = null

    fun notify(title: String, text: String) {
        trayState?.sendNotification(Notification(title, text, Notification.Type.Info))
    }
}

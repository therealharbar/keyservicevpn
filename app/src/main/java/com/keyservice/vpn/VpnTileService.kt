package com.keyservice.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import dev.dev7.lib.v2ray.V2rayController
import dev.dev7.lib.v2ray.utils.V2rayConstants
import dev.dev7.lib.v2ray.utils.V2rayConfigs
import android.graphics.drawable.Icon

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val v2rayStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Обновим состояние из extras (как делает V2rayController.registerReceivers)
            try {
                val s = intent.extras
                    ?.getSerializable(V2rayConstants.SERVICE_CONNECTION_STATE_BROADCAST_EXTRA)
                        as? V2rayConstants.CONNECTION_STATES

                if (s != null) {
                    V2rayConfigs.connectionState = s
                }
            } catch (_: Exception) {}

            updateTileState()
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        // Подписываемся на broadcast статуса сервиса
        try {
            val filter = IntentFilter(V2rayConstants.V2RAY_SERVICE_STATICS_BROADCAST_INTENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(v2rayStateReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(v2rayStateReceiver, filter)
            }
        } catch (_: Exception) {}

        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        try { unregisterReceiver(v2rayStateReceiver) } catch (_: Exception) {}
    }

    override fun onClick() {
        super.onClick()

        val state = V2rayConfigs.connectionState
        val isConnected = (state == V2rayConstants.CONNECTION_STATES.CONNECTED)

        if (isConnected) {
            V2rayController.stopV2ray(this)
            updateTileState()
            return
        }

        val prefs = getSharedPreferences(Prefs.PREFS_NAME, MODE_PRIVATE)
        val trojanUri = prefs.getString(Prefs.KEY_LAST_TROJAN, "")?.trim().orEmpty()

        if (trojanUri.isEmpty() || !trojanUri.startsWith("trojan://")) {
            Toast.makeText(this, "Открой приложение и вставь ключ", Toast.LENGTH_LONG).show()
            openMainActivity()
            return
        }

        // TileService не может показать системный диалог VPN-разрешения
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            Toast.makeText(this, "Нужно разрешить VPN — открою приложение", Toast.LENGTH_LONG).show()
            openMainActivity()
            return
        }

        // ВАЖНО: используем Context-метод (deprecated), иначе будет Activity mismatch
        val remark = parseRemarkFromTrojanUri(trojanUri)
        val blockedApps = arrayListOf<String>()

        V2rayController.StartV2ray(
            this,
            remark,
            trojanUri,
            blockedApps
        )

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        val state = V2rayConfigs.connectionState
        val isConnected = (state == V2rayConstants.CONNECTION_STATES.CONNECTED)

        tile.state = if (isConnected) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }

        tile.label = "KeyService VPN"

        // 🔥 ВОТ ЗДЕСЬ КАСТОМНАЯ ИКОНКА
        tile.icon = Icon.createWithResource(
            this,
            R.drawable.ic_keyservice_tile
        )

        tile.updateTile()
    }


    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(intent)
    }

    private fun parseRemarkFromTrojanUri(uri: String): String {
        val remark = uri.substringAfter("#", "")
        return if (remark.isNotBlank()) remark else "KeyService VPN"
    }
}

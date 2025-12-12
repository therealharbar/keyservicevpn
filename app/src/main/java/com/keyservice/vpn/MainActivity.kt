package com.keyservice.vpn

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.animation.CycleInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.service.quicksettings.TileService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.dev7.lib.v2ray.V2rayController
import dev.dev7.lib.v2ray.interfaces.LatencyDelayListener
import dev.dev7.lib.v2ray.utils.V2rayConstants

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var pingText: TextView

    private lateinit var trojanInputLayout: TextInputLayout
    private lateinit var trojanInput: TextInputEditText
    private lateinit var keySavedText: TextView
    private lateinit var pasteButton: MaterialButton

    private val prefs by lazy { getSharedPreferences(Prefs.PREFS_NAME, MODE_PRIVATE) }

    private var isConnected: Boolean = false
    private var isBusy: Boolean = false

    private var ignoreTextWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        V2rayController.init(
            this,
            R.mipmap.ic_launcher,
            "KeyService VPN"
        )

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        pingText = findViewById(R.id.pingText)

        trojanInputLayout = findViewById(R.id.trojanInputLayout)
        trojanInput = findViewById(R.id.trojanInput)
        keySavedText = findViewById(R.id.keySavedText)
        pasteButton = findViewById(R.id.pasteButton)

        // 1) Подставляем сохранённый ключ (если он нормальный)
        val saved = prefs.getString(Prefs.KEY_LAST_TROJAN, "")?.trim().orEmpty()
        if (isValidTrojanUri(saved)) {
            setTrojanTextSilently(saved)
            showKeySaved(true, "Ключ сохранён ✓")
        } else {
            showKeySaved(false)
        }

        // 2) TextWatcher: валидируем + сохраняем (НО только валидное, иначе не перезаписываем)
        trojanInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextWatcher) return

                val v = s?.toString()?.trim().orEmpty()

                // убираем ошибку, пока человек печатает
                trojanInputLayout.error = null

                if (v.isBlank()) {
                    // пустое — не затираем сохранённый ключ
                    showKeySaved(false)
                    requestTileRefresh()
                    return
                }

                if (isValidTrojanUri(v)) {
                    prefs.edit().putString(Prefs.KEY_LAST_TROJAN, v).apply()
                    showKeySaved(true, "Ключ сохранён ✓")
                    requestTileRefresh()
                } else {
                    // невалидное — не сохраняем, просто уберём "saved"
                    showKeySaved(false)
                    requestTileRefresh()
                }
            }
        })

        // 3) Вставка из буфера
        pasteButton.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()

            if (text.isBlank()) {
                Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setTrojanTextSilently(text)
            trojanInput.setSelection(text.length)

            if (!isValidTrojanUri(text)) {
                trojanInputLayout.error = "Нужна ссылка вида trojan://..."
                shakeView(trojanInputLayout)
                showKeySaved(false)
            } else {
                // сохранится через watcher
                Toast.makeText(this, "Вставлено из буфера", Toast.LENGTH_SHORT).show()
            }
        }

        // 4) Ping кликабельный → обновить
        pingText.setOnClickListener { updatePing(force = true) }

        updateUiByState()
        updatePing()
        requestTileRefresh()

        // 5) Основная кнопка Connect / Disconnect
        toggleButton.setOnClickListener {
            if (isBusy) return@setOnClickListener

            val state = V2rayController.getConnectionState()
            isConnected = (state == V2rayConstants.CONNECTION_STATES.CONNECTED)

            Log.d("KeyServiceVPN", "toggle click, state=$state isConnected=$isConnected")

            if (!isConnected) {
                val trojanUri = trojanInput.text?.toString()?.trim().orEmpty()

                if (trojanUri.isBlank()) {
                    trojanInputLayout.error = "Место для ключа"
                    shakeView(trojanInputLayout)
                    Toast.makeText(this, "Место для ключа", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!isValidTrojanUri(trojanUri)) {
                    trojanInputLayout.error = "Нужна ссылка вида trojan://..."
                    shakeView(trojanInputLayout)
                    Toast.makeText(this, "Нужна ссылка вида trojan://...", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Явно сохраняем перед подключением
                prefs.edit().putString(Prefs.KEY_LAST_TROJAN, trojanUri).apply()
                showKeySaved(true, "Ключ сохранён ✓")
                requestTileRefresh()

                val remark = parseRemarkFromTrojanUri(trojanUri)
                val blockedApps = arrayListOf<String>()

                isBusy = true
                statusText.text = "Статус: 🟡 Подключение…"
                toggleButton.isEnabled = false

                V2rayController.startV2ray(
                    this,
                    remark,
                    trojanUri,
                    blockedApps
                )

                statusText.postDelayed({
                    isBusy = false
                    toggleButton.isEnabled = true
                    updateUiByState()
                    updatePing()
                    requestTileRefresh()
                }, 1200)

            } else {
                // Отключаемся
                isBusy = true
                statusText.text = "Статус: 🟡 Отключение…"
                toggleButton.isEnabled = false
                pingText.text = "Пинг: —"

                V2rayController.stopV2ray(this)

                statusText.postDelayed({
                    isBusy = false
                    toggleButton.isEnabled = true
                    updateUiByState()
                    requestTileRefresh()
                }, 900)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        V2rayController.registerReceivers(this)
        updateUiByState()
        updatePing()
        requestTileRefresh()
    }

    private fun updateUiByState() {
        val state = V2rayController.getConnectionState()
        isConnected = (state == V2rayConstants.CONNECTION_STATES.CONNECTED)

        val statusTextValue = when (state) {
            V2rayConstants.CONNECTION_STATES.CONNECTED -> "Статус: 🟢 Подключено"
            V2rayConstants.CONNECTION_STATES.CONNECTING -> "Статус: 🟡 Подключение…"
            V2rayConstants.CONNECTION_STATES.DISCONNECTED -> "Статус: 🔴 Отключено"
            else -> "Статус: ⚪ Неизвестно"
        }

        statusText.text = statusTextValue
        toggleButton.text = if (isConnected) "Отключить VPN" else "Подключить VPN"
    }

    private fun updatePing(force: Boolean = false) {
        val state = V2rayController.getConnectionState()
        if (state != V2rayConstants.CONNECTION_STATES.CONNECTED) {
            pingText.text = "Пинг: —"
            return
        }

        if (force) pingText.text = "Пинг: измеряю…"

        V2rayController.getConnectedV2rayServerDelay(
            this,
            LatencyDelayListener { delay ->
                runOnUiThread {
                    pingText.text =
                        if (delay >= 0) "Пинг: ${delay} мс (нажми чтобы обновить)"
                        else "Пинг: недоступен (нажми чтобы обновить)"
                }
            }
        )
    }

    private fun parseRemarkFromTrojanUri(uri: String): String {
        val remark = uri.substringAfter("#", "")
        return if (remark.isNotBlank()) remark else "KeyService VPN"
    }

    private fun isValidTrojanUri(s: String): Boolean {
        // минимальная проверка, без “умного” парсинга:
        // trojan://<pass>@<host>:<port>...
        if (!s.startsWith("trojan://")) return false
        return s.contains("@") && s.contains(":")
    }

    private fun setTrojanTextSilently(text: String) {
        ignoreTextWatcher = true
        trojanInput.setText(text)
        ignoreTextWatcher = false
    }

    private fun showKeySaved(show: Boolean, text: String = "") {
        keySavedText.visibility = if (show) View.VISIBLE else View.GONE
        if (show) keySavedText.text = text
    }

    private fun shakeView(v: View) {
        v.animate()
            .translationX(0f)
            .setDuration(0)
            .start()

        v.animate()
            .translationX(12f)
            .setInterpolator(CycleInterpolator(6f))
            .setDuration(380)
            .withEndAction { v.animate().translationX(0f).setDuration(0).start() }
            .start()
    }

    private fun requestTileRefresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cn = ComponentName(this, VpnTileService::class.java)
            TileService.requestListeningState(this, cn)
        }
    }
}

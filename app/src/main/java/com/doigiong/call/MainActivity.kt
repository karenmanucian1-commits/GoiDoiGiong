package com.doigiong.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var engine: CallEngine
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "IP máy này: ${myIp()}"
            textSize = 20f
        })

        val ipInput = EditText(this).apply {
            hint = "Nhập IP máy kia (vd 192.168.1.5)"
            inputType = InputType.TYPE_CLASS_PHONE
            getSharedPreferences("p", MODE_PRIVATE).getString("ip", "")?.let { setText(it) }
        }
        root.addView(ipInput)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnCall = Button(this).apply { text = "GỌI" }
        val btnEnd = Button(this).apply { text = "CÚP MÁY" }
        row.addView(btnCall, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btnEnd, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        val speaker = Switch(this).apply { text = "Loa ngoài" }
        root.addView(speaker)

        status = TextView(this).apply {
            text = "Sẵn sàng. Mở app trên cả 2 máy."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        root.addView(TextView(this).apply { text = "Chọn giọng:"; textSize = 18f })
        val group = RadioGroup(this)
        Voice.values().forEachIndexed { i, v ->
            group.addView(RadioButton(this).apply { id = i + 1; text = v.label; textSize = 18f })
        }
        group.check(1)
        root.addView(group)

        setContentView(root)
        root.gravity = Gravity.TOP

        engine = CallEngine { msg -> runOnUiThread { status.text = msg } }

        group.setOnCheckedChangeListener { _, id -> engine.effect.voice = Voice.values()[id - 1] }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speaker.setOnCheckedChangeListener { _, on ->
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }

        btnCall.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("ip", ip).apply()
            engine.call(ip)
        }
        btnEnd.setOnClickListener { engine.hangUp() }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else start(am)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            start(getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        } else status.text = "Cần cấp quyền micro để gọi"
    }

    private fun start(am: AudioManager) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        engine.start()
    }

    override fun onDestroy() {
        engine.stop()
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    private fun myIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .sortedBy { if (it.name.startsWith("wlan") || it.name.contains("ap")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "không có wifi"
    } catch (e: Exception) { "?" }
}

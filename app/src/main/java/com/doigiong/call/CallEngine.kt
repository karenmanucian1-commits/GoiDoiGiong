package com.doigiong.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.wifi.WifiManager
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Gọi thoại qua UDP trong cùng mạng wifi.
 * Gói tin: [loại][số thứ tự 2 byte][PCM 16-bit]. loại 1 = âm thanh, 3 = kết thúc.
 * Máy nhận tự động bắt máy khi có âm thanh tới.
 */
class CallEngine(
    context: Context,
    private val onState: (String) -> Unit
) {
    companion object {
        const val PORT = 50005
        const val RATE = 16000
        const val FRAME = 320          // 20ms
        const val HDR = 3
    }

    val effect = VoiceEffect(RATE)

    @Volatile private var peer: InetAddress? = null
    @Volatile private var inCall = false
    @Volatile private var running = false
    @Volatile private var endedAt = 0L
    @Volatile private var farLevel = 0f        // độ lớn tiếng bên kia đang phát ra loa
    @Volatile private var farHoldUntil = 0L
    private var socket: DatagramSocket? = null

    // thống kê mạng
    @Volatile private var lastSeq = -1
    @Volatile private var got = 0
    @Volatile private var lost = 0

    private val jitter = LinkedBlockingQueue<ShortArray>()

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
            if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "goidoigiong"
        )

    fun start() {
        if (running) return
        running = true
        socket = DatagramSocket(PORT).apply {
            try { receiveBufferSize = 65536 } catch (_: Exception) { }
        }
        Thread { receiveLoop() }.start()
        Thread { playLoop() }.start()
    }

    fun call(ip: String) {
        Thread {
            try {
                peer = InetAddress.getByName(ip)
                beginCall()
            } catch (e: Exception) {
                onState("IP không hợp lệ")
            }
        }.start()
    }

    fun hangUp() {
        val p = peer
        if (p != null) Thread { sendRaw(byteArrayOf(3, 0, 0), p) }.start()
        endCall("Đã kết thúc")
    }

    fun stop() {
        hangUp()
        running = false
        socket?.close()
    }

    private fun beginCall() {
        if (inCall) return
        inCall = true
        lastSeq = -1; got = 0; lost = 0
        try { wifiLock.acquire() } catch (_: Exception) { }
        onState("Đang gọi với ${peer?.hostAddress}")
        Thread { sendLoop() }.start()
        Thread { statsLoop() }.start()
    }

    private fun endCall(msg: String) {
        inCall = false
        endedAt = System.currentTimeMillis()
        try { if (wifiLock.isHeld) wifiLock.release() } catch (_: Exception) { }
        onState(msg)
    }

    /** Mỗi 2 giây báo chất lượng mạng lên màn hình. */
    private fun statsLoop() {
        while (inCall) {
            Thread.sleep(2000)
            if (!inCall) break
            val total = got + lost
            val loss = if (total > 0) lost * 100 / total else 0
            val q = when {
                got == 0 -> "chưa nhận được tiếng"
                loss <= 2 -> "mạng tốt"
                loss <= 8 -> "mạng hơi chập chờn"
                else -> "mạng kém"
            }
            onState("Đang gọi với ${peer?.hostAddress}\n$q (mất $loss% gói)")
            got = 0; lost = 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLoop() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME * 4)
        )
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
        aec?.enabled = true
        rec.startRecording()
        val pcm = ShortArray(FRAME)
        val out = ByteArray(HDR + FRAME * 2)
        out[0] = 1
        var seq = 0
        var g = 1f
        var micLevel = 0f
        while (inCall) {
            val n = rec.read(pcm, 0, FRAME)
            if (n <= 0) continue

            // Chặn tiếng vọng: khi loa đang phát tiếng bên kia thì hạ micro xuống,
            // trừ khi mình nói to rõ (nói chen).
            // Máy đang nói (micro to hơn tiếng loa) thì KHÔNG bị giảm.
            // Chỉ giảm khi tiếng loa bên kia lấn át micro, tức micro chỉ đang thu tiếng vọng.
            var e = 0f
            for (i in 0 until n) { val v = pcm[i] / 32768f; e += v * v }
            val micNow = sqrt(e / n)
            micLevel = if (micNow > micLevel) micNow else micLevel * 0.85f
            val far = farLevel
            val farActive = far > 0.02f || System.currentTimeMillis() < farHoldUntil
            // Chỉ giảm nhẹ micro khi loa đang phát tiếng bên kia to hơn hẳn tiếng mình nói
            val target = if (farActive && micLevel < far * 0.8f) 0.3f else 1f
            for (i in 0 until n) {
                g += (target - g) * (if (target > g) 0.02f else 0.002f)   // mở micro nhanh, giảm chậm
                pcm[i] = (pcm[i] * g).toInt().toShort()
            }

            effect.process(pcm, n)
            out[1] = (seq and 0xFF).toByte()
            out[2] = ((seq shr 8) and 0xFF).toByte()
            seq = (seq + 1) and 0xFFFF
            for (i in 0 until n) {
                out[HDR + i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                out[HDR + 1 + i * 2] = (pcm[i].toInt() shr 8).toByte()
            }
            peer?.let { sendRaw(out, it, HDR + n * 2) }
        }
        rec.stop()
        aec?.release()
        rec.release()
    }

    private fun receiveLoop() {
        val data = ByteArray(2048)
        val pkt = DatagramPacket(data, data.size)
        val s = socket ?: return
        while (running) {
            try {
                s.receive(pkt)
            } catch (e: Exception) {
                break
            }
            when (data[0].toInt()) {
                1 -> {
                    if (!inCall) {            // tự bắt máy
                        if (System.currentTimeMillis() - endedAt < 2000) continue
                        peer = pkt.address
                        beginCall()
                    }
                    val seq = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                    if (lastSeq >= 0) {
                        val gap = (seq - lastSeq - 1) and 0xFFFF
                        if (gap in 1..50) lost += gap
                    }
                    lastSeq = seq
                    got++
                    val n = (pkt.length - HDR) / 2
                    val pcm = ShortArray(n)
                    for (i in 0 until n) {
                        pcm[i] = ((data[HDR + i * 2].toInt() and 0xFF) or
                                (data[HDR + 1 + i * 2].toInt() shl 8)).toShort()
                    }
                    jitter.offer(pcm)
                    while (jitter.size > 20) jitter.poll()      // dồn quá 400ms thì bỏ bớt
                }
                3 -> if (inCall) endCall("Bên kia đã cúp máy")
            }
        }
    }

    /** Phát tiếng bên kia, có bộ đệm chống giật ~120ms. */
    private fun playLoop() {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(min * 2, FRAME * 16),
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
        val silence = ShortArray(FRAME)
        var buffering = true
        var pg = 1f
        while (running) {
            if (!inCall) {
                jitter.clear(); buffering = true; farLevel = 0f
                Thread.sleep(20); continue
            }
            if (buffering) {
                if (jitter.size < 6) { Thread.sleep(10); continue }   // gom ~120ms rồi mới phát
                buffering = false
            }
            // nếu hàng đợi dồn lâu thì bỏ 1 gói để giảm độ trễ
            if (jitter.size > 12) jitter.poll()
            val pcm = jitter.poll(80, TimeUnit.MILLISECONDS)
            if (pcm == null) {
                track.write(silence, 0, FRAME)
                farLevel *= 0.5f
                buffering = true
            } else {
                var e = 0f
                for (v in pcm) { val f = v / 32768f; e += f * f }
                val lvl = sqrt(e / pcm.size)
                // Tự tăng âm lượng tiếng nói nhỏ (tối đa 3 lần), không tăng tiếng ồn nền
                if (lvl > 0.01f) {
                    val t = (0.15f / lvl).coerceIn(1f, 3f)
                    pg += (t - pg) * 0.15f
                }
                for (i in pcm.indices) {
                    var y = pcm[i] / 32768f * pg
                    val a = abs(y)
                    if (a > 0.7f) y = sign(y) * (0.7f + 0.3f * tanh((a - 0.7f) / 0.3f))   // chặn đỉnh mềm
                    pcm[i] = (y * 32767f).toInt().toShort()
                }
                val outLvl = lvl * pg
                farLevel = if (outLvl > farLevel) outLvl else farLevel * 0.9f
                if (outLvl > 0.02f) farHoldUntil = System.currentTimeMillis() + 120
                track.write(pcm, 0, pcm.size)
            }
        }
        track.stop()
        track.release()
    }

    private fun sendRaw(b: ByteArray, to: InetAddress, len: Int = b.size) {
        try {
            socket?.send(DatagramPacket(b, len, to, PORT))
        } catch (_: Exception) { }
    }
}

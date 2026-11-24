package com.doigiong.call

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Gọi thoại qua UDP trong cùng mạng wifi.
 * Gói tin: byte đầu = loại (1 = âm thanh, 3 = kết thúc), phần còn lại = PCM 16-bit.
 * Máy nhận tự động bắt máy khi có âm thanh tới.
 */
class CallEngine(
    private val onState: (String) -> Unit
) {
    companion object {
        const val PORT = 50005
        const val RATE = 16000
        const val FRAME = 320          // 20ms
    }

    val effect = VoiceEffect(RATE)

    @Volatile private var peer: InetAddress? = null
    @Volatile private var inCall = false
    @Volatile private var running = false
    @Volatile private var endedAt = 0L
    private var socket: DatagramSocket? = null
    private var sendThread: Thread? = null

    /** Bật lắng nghe khi mở app. */
    fun start() {
        if (running) return
        running = true
        socket = DatagramSocket(PORT)
        Thread { receiveLoop() }.start()
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
        if (p != null) Thread { sendRaw(byteArrayOf(3), p) }.start()
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
        onState("Đang gọi với ${peer?.hostAddress}")
        sendThread = Thread { sendLoop() }.also { it.start() }
    }

    private fun endCall(msg: String) {
        inCall = false
        endedAt = System.currentTimeMillis()
        onState(msg)
    }

    @SuppressLint("MissingPermission")
    private fun sendLoop() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME * 4)
        )
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        rec.startRecording()
        val pcm = ShortArray(FRAME)
        val out = ByteArray(1 + FRAME * 2)
        out[0] = 1
        while (inCall) {
            val n = rec.read(pcm, 0, FRAME)
            if (n <= 0) continue
            effect.process(pcm, n)
            for (i in 0 until n) {
                out[1 + i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                out[2 + i * 2] = (pcm[i].toInt() shr 8).toByte()
            }
            peer?.let { sendRaw(out, it, 1 + n * 2) }
        }
        rec.stop()
        rec.release()
    }

    /** Hàng đợi chống giật: gom vài gói trước khi phát để wifi chậm không gây rè. */
    private val jitter = java.util.concurrent.LinkedBlockingQueue<ShortArray>()

    private fun receiveLoop() {
        Thread { playLoop() }.start()
        val data = ByteArray(2048)
        val pkt = DatagramPacket(data, data.size)
        val s = socket ?: return
        try { s.receiveBufferSize = 65536 } catch (_: Exception) { }
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
                    val n = (pkt.length - 1) / 2
                    val pcm = ShortArray(n)
                    for (i in 0 until n) {
                        pcm[i] = ((data[1 + i * 2].toInt() and 0xFF) or (data[2 + i * 2].toInt() shl 8)).toShort()
                    }
                    jitter.offer(pcm)
                    while (jitter.size > 12) jitter.poll()     // bỏ bớt nếu dồn quá (>240ms)
                }
                3 -> if (inCall) endCall("Bên kia đã cúp máy")
            }
        }
    }

    private fun playLoop() {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, FRAME * 16), AudioTrack.MODE_STREAM
        )
        track.play()
        val silence = ShortArray(FRAME)
        var buffering = true
        while (running) {
            if (!inCall) { jitter.clear(); buffering = true; Thread.sleep(20); continue }
            if (buffering) {
                if (jitter.size < 4) { Thread.sleep(10); continue }   // gom đủ ~80ms rồi mới phát
                buffering = false
            }
            val pcm = jitter.poll(60, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (pcm == null) {
                track.write(silence, 0, FRAME)
                buffering = true
            } else {
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

package com.doigiong.call

import kotlin.math.PI
import kotlin.math.sin

/**
 * Các giọng có sẵn.
 * ratio  = hệ số cao độ so với giọng thật (1.0 = giữ nguyên, >1 cao hơn, <1 trầm hơn)
 * shake  = độ run giọng (0 = không run), dùng cho giọng già
 * Nếu giọng thật của bạn là nữ, giảm các ratio xuống khoảng 0.75 lần.
 */
enum class Voice(val label: String, val ratio: Float, val shake: Float = 0f) {
    GOC("Gốc", 1.0f),
    CON_GAI("Con gái", 1.60f),
    PHU_NU_TRUNG_NIEN("Phụ nữ trung niên", 1.36f),
    BA_GIA("Bà già", 1.26f, shake = 0.035f),
    DAN_ONG_TRUNG_NIEN("Đàn ông trung niên", 0.88f),
    ONG_GIA("Ông già", 0.74f, shake = 0.04f)
}

/**
 * Đổi cao độ giọng theo thời gian thực (độ trễ ~40ms) bằng hai đầu đọc
 * trên bộ đệm trễ, đan chéo nhau để không bị tiếng lục cục.
 * Không dùng thư viện ngoài. Xử lý PCM 16-bit mono.
 */
class VoiceEffect(private val sampleRate: Int) {

    @Volatile var voice: Voice = Voice.GOC

    private val bufSize = 4096
    private val buf = FloatArray(bufSize)
    private var writeIdx = 0
    private val window = sampleRate * 0.040f   // cửa sổ 40ms
    private var phase = 0f
    private var shakePhase = 0.0
    private val shakeStep = 2.0 * PI * 5.5 / sampleRate   // run 5.5 lần/giây

    fun process(pcm: ShortArray, n: Int) {
        val v = voice
        if (v == Voice.GOC) return
        for (i in 0 until n) {
            buf[writeIdx] = pcm[i] / 32768f

            var ratio = v.ratio
            var gain = 0.75f
            if (v.shake > 0f) {
                val s = sin(shakePhase).toFloat()
                ratio *= 1f + v.shake * s          // cao độ dao động
                gain *= 1f + 0.12f * s             // âm lượng dao động nhẹ
                shakePhase += shakeStep
                if (shakePhase > 2.0 * PI) shakePhase -= 2.0 * PI
            }

            phase += 1f - ratio
            if (phase >= window) phase -= window
            if (phase < 0f) phase += window
            var d2 = phase + window / 2f
            if (d2 >= window) d2 -= window
            val g1 = sin(PI * phase / window).toFloat()
            val g2 = sin(PI * d2 / window).toFloat()
            val y = (read(phase + 1f) * g1 + read(d2 + 1f) * g2) * gain

            writeIdx = (writeIdx + 1) % bufSize
            pcm[i] = (y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    /** Đọc mẫu cách vị trí ghi hiện tại `delay` mẫu, nội suy tuyến tính. */
    private fun read(delay: Float): Float {
        var pos = writeIdx - delay
        while (pos < 0) pos += bufSize
        val i0 = pos.toInt() % bufSize
        val i1 = (i0 + 1) % bufSize
        val f = pos - pos.toInt()
        return buf[i0] * (1f - f) + buf[i1] * f
    }
}

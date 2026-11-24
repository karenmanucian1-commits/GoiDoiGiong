package com.doigiong.call

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Các giọng (tính cho người nói là nam, giọng gốc ~120Hz).
 * pitch   = hệ số cao độ (giọng nữ ~200Hz, nam ~125Hz)
 * formant = hệ số âm sắc/khoang miệng (nữ cao hơn nam ~10-20%)
 * shake   = độ run giọng (người già run cao độ ~5Hz)
 */
enum class Voice(val label: String, val pitch: Float, val formant: Float, val shake: Float = 0f) {
    GOC("Gốc", 1f, 1f),
    CON_GAI("Con gái", 1.90f, 1.18f),
    PHU_NU_TRUNG_NIEN("Phụ nữ trung niên", 1.60f, 1.12f),
    BA_GIA("Bà già", 1.42f, 1.10f, shake = 0.03f),
    DAN_ONG_TRUNG_NIEN("Đàn ông trung niên", 0.88f, 0.95f),
    ONG_GIA("Ông già", 0.97f, 0.93f, shake = 0.035f)
}

/**
 * Đổi giọng theo thời gian thực bằng PSOLA:
 * dò chu kỳ giọng (YIN), cắt từng hạt dài 2 chu kỳ, co giãn hạt để đổi âm sắc,
 * rồi đặt lại các hạt với khoảng cách mới để đổi cao độ. Trễ ~50ms.
 */
class VoiceEffect(private val sr: Int) {

    @Volatile var voice: Voice = Voice.GOC

    private val N = 8192
    private val raw = FloatArray(N)      // tiếng gốc
    private val inb = FloatArray(N)      // tiếng gốc đã lọc bỏ âm quá cao (chống rè khi nâng âm sắc)
    private val out = FloatArray(N)
    private var inCount = 0L
    private var synth = 600.0
    private var ana = 0.0
    private var period = 160.0
    private var voiced = false
    private var unvoicedRun = 0
    private val lastP = DoubleArray(3) { 160.0 }
    private var shakePh = 0.0
    private var gain = 1f
    private val D = 300
    private val EMIT = 500

    // bộ lọc thấp qua (biquad)
    private var lpVoice: Voice? = null
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    // bộ đệm cho YIN
    private val W = 400
    private val TMIN = 32
    private val TMAX = 230
    private val x = FloatArray(W + TMAX)
    private val d = FloatArray(TMAX + 1)

    private fun setupFilter(v: Voice) {
        lpVoice = v
        val fc = if (v.formant > 1f) (7200f / v.formant) else 7000f
        val w0 = 2.0 * PI * fc / sr
        val alpha = sin(w0) / (2 * 0.707)
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = ((1 - c) / 2 / a0).toFloat(); b1 = ((1 - c) / a0).toFloat(); b2 = b0
        a1 = (-2 * c / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
    }

    fun process(pcm: ShortArray, n: Int) {
        val v = voice
        if (v != lpVoice) setupFilter(v)
        for (k in 0 until n) {
            val s = pcm[k] / 32768f
            val idx = ((inCount + k) % N).toInt()
            raw[idx] = s
            val y = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = s; y2 = y1; y1 = y
            inb[idx] = y
        }
        inCount += n
        if (v == Voice.GOC) {          // không đổi: giữ đồng bộ bộ đệm
            synth = inCount + 300.0; ana = synth - D; out.fill(0f)
            return
        }
        detect()

        while (synth - D + 300 < inCount) {
            val tin = if (voiced) period else 160.0
            var p = v.pitch.toDouble()
            if (v.shake > 0f) {
                p *= 1 + v.shake * sin(shakePh)
                shakePh += 2 * PI * 5.0 * (tin / p) / sr
                if (shakePh > 2 * PI) shakePh -= 2 * PI
            }
            val tout = if (voiced) tin / p else 160.0
            val tgt = synth - D
            if (voiced) {
                if (abs(tgt - ana) > 3 * tin) ana = tgt
                while (ana + tin / 2 <= tgt) ana += tin
            } else ana = tgt

            val L = tin.toInt()
            val norm = (tout / L).toFloat()
            val c = synth.roundToInt()
            val f = v.formant
            for (j in -L..L) {
                val w = 0.5f * (1f + cos(PI * j / L).toFloat())
                val idx = ((c + j) % N + N) % N
                out[idx] += w * read(ana + j * f) * norm
            }
            synth += tout
        }

        // xuất mẫu + cân bằng âm lượng cho bằng giọng gốc (đổi mượt từng mẫu)
        val st = inCount - n - EMIT
        var eIn = 0f; var eOut = 0f
        val tmp = FloatArray(n)
        for (k in 0 until n) {
            val i = (((st + k) % N + N) % N).toInt()
            tmp[k] = out[i]; out[i] = 0f
            val xi = raw[(((st + k - D) % N + N) % N).toInt()]
            eIn += xi * xi; eOut += tmp[k] * tmp[k]
        }
        var target = gain
        if (eOut > 1e-6f && eIn > 1e-6f) target = sqrt(eIn / eOut).coerceIn(0.7f, 2.0f)
        val step = (target - gain) * 0.3f / n
        for (k in 0 until n) {
            gain += step
            pcm[k] = (tmp[k] * gain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    private fun read(p: Double): Float {
        val i = floor(p).toLong()
        val fr = (p - i).toFloat()
        val a = inb[((i % N + N) % N).toInt()]
        val b = inb[(((i + 1) % N + N) % N).toInt()]
        return a * (1 - fr) + b * fr
    }

    /** Dò chu kỳ giọng bằng thuật toán YIN, có làm mượt để không nhảy giọng. */
    private fun detect() {
        if (inCount < W + TMAX) return
        val st = inCount - W - TMAX
        var e = 0f
        for (k in x.indices) x[k] = inb[(((st + k) % N + N) % N).toInt()]
        for (k in 0 until W) e += x[k] * x[k]
        var best = -1
        if (e / W >= 1e-5f) {
            var run = 0f
            for (t in 1..TMAX) {
                var s = 0f
                for (k in 0 until W) { val df = x[k] - x[k + t]; s += df * df }
                run += s
                d[t] = if (run > 0f) s * t / run else 1f
            }
            var t = TMIN
            while (t < TMAX) {
                if (d[t] < 0.25f) {
                    while (t + 1 < TMAX && d[t + 1] < d[t]) t++
                    best = t; break
                }
                t++
            }
        }
        if (best < 0) {
            // cần 2 khung liền không có giọng mới chuyển sang "không giọng" (tránh chập chờn)
            if (++unvoicedRun >= 2) voiced = false
            return
        }
        unvoicedRun = 0
        lastP[0] = lastP[1]; lastP[1] = lastP[2]; lastP[2] = best.toDouble()
        val med = lastP.sorted()[1]
        period = if (voiced) 0.6 * period + 0.4 * med else best.toDouble()
        voiced = true
    }
}

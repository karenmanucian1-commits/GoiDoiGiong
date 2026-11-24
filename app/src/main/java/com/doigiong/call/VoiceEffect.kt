package com.doigiong.call

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Các giọng (tính cho người nói là nam, giọng gốc ~120Hz).
 * pitch   = hệ số cao độ (giọng nữ ~200Hz, nam ~125Hz)
 * formant = hệ số âm sắc/khoang miệng (nữ cao hơn nam ~10-20%)
 * shake   = độ run giọng (người già run cao độ ~5Hz)
 * breath  = độ khàn/hụt hơi
 */
enum class Voice(
    val label: String, val pitch: Float, val formant: Float,
    val shake: Float = 0f, val breath: Float = 0f
) {
    GOC("Gốc", 1f, 1f),
    CON_GAI("Con gái", 1.90f, 1.18f),
    PHU_NU_TRUNG_NIEN("Phụ nữ trung niên", 1.60f, 1.12f),
    BA_GIA("Bà già", 1.42f, 1.10f, shake = 0.045f, breath = 0.10f),
    DAN_ONG_TRUNG_NIEN("Đàn ông trung niên", 0.88f, 0.95f),
    ONG_GIA("Ông già", 0.97f, 0.93f, shake = 0.055f, breath = 0.14f)
}

/**
 * Đổi giọng theo thời gian thực bằng PSOLA:
 * dò chu kỳ giọng (YIN), cắt từng hạt dài 2 chu kỳ, co giãn hạt để đổi âm sắc,
 * rồi đặt lại các hạt với khoảng cách mới để đổi cao độ. Trễ ~50ms.
 */
class VoiceEffect(private val sr: Int) {

    @Volatile var voice: Voice = Voice.GOC

    private val N = 8192
    private val inb = FloatArray(N)
    private val out = FloatArray(N)
    private var inCount = 0L
    private var synth = 600.0
    private var ana = 0.0
    private var period = 160.0
    private var voiced = false
    private var shakePh = 0.0
    private var gain = 1f
    private val D = 300
    private val EMIT = 500

    // bộ đệm cho YIN
    private val W = 400
    private val TMIN = 32
    private val TMAX = 230
    private val x = FloatArray(W + TMAX)
    private val d = FloatArray(TMAX + 1)

    fun process(pcm: ShortArray, n: Int) {
        val v = voice
        for (k in 0 until n) inb[((inCount + k) % N).toInt()] = pcm[k] / 32768f
        inCount += n
        if (v == Voice.GOC) {          // không đổi: vẫn chạy để giữ đồng bộ bộ đệm
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

        // xuất mẫu + cân bằng âm lượng cho bằng giọng gốc
        val st = inCount - n - EMIT
        var eIn = 0f; var eOut = 0f
        val tmp = FloatArray(n)
        for (k in 0 until n) {
            val i = (((st + k) % N + N) % N).toInt()
            tmp[k] = out[i]; out[i] = 0f
            val xi = inb[(((st + k - D) % N + N) % N).toInt()]
            eIn += xi * xi; eOut += tmp[k] * tmp[k]
        }
        if (eOut > 1e-7f) {
            val target = (sqrt(eIn / eOut)).coerceIn(0.5f, 4f)
            gain = 0.9f * gain + 0.1f * target
        }
        val envel = sqrt(eIn / n)
        for (k in 0 until n) {
            var y = tmp[k] * gain
            if (v.breath > 0f) y += (Random.nextFloat() * 2f - 1f) * envel * v.breath * 2f
            pcm[k] = (y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    private fun read(p: Double): Float {
        val i = floor(p).toLong()
        val fr = (p - i).toFloat()
        val a = inb[((i % N + N) % N).toInt()]
        val b = inb[(((i + 1) % N + N) % N).toInt()]
        return a * (1 - fr) + b * fr
    }

    /** Dò chu kỳ giọng bằng thuật toán YIN. */
    private fun detect() {
        if (inCount < W + TMAX) return
        val st = inCount - W - TMAX
        var e = 0f
        for (k in x.indices) x[k] = inb[(((st + k) % N + N) % N).toInt()]
        for (k in 0 until W) e += x[k] * x[k]
        if (e / W < 1e-5f) { voiced = false; return }
        var run = 0f
        for (t in 1..TMAX) {
            var s = 0f
            for (k in 0 until W) { val df = x[k] - x[k + t]; s += df * df }
            run += s
            d[t] = if (run > 0f) s * t / run else 1f
        }
        var best = -1
        var t = TMIN
        while (t < TMAX) {
            if (d[t] < 0.2f) {
                while (t + 1 < TMAX && d[t + 1] < d[t]) t++
                best = t; break
            }
            t++
        }
        if (best < 0) { voiced = false; return }
        voiced = true
        period = if (abs(period - best) < 0.25 * best) 0.7 * period + 0.3 * best else best.toDouble()
    }
}

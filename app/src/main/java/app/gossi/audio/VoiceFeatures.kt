package app.gossi.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A compact "voiceprint" of one utterance: average cepstrum (timbre) + median pitch. */
class VoiceFeature(val ceps: DoubleArray, val pitchHz: Double)

/** In-place radix-2 FFT. */
class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = -sinT[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Collects voice features from every voiced mic frame between two recognition results.
 * Called from the mic thread (addFrame) and the main thread (take/reset), hence synchronized.
 */
class VoiceFeatureAccumulator(private val sampleRate: Int = 16000) {
    companion object {
        private const val N = 512
        private const val MELS = 24
        private const val CEPS = 12
        private const val MIN_VOICED_FRAMES = 15
    }

    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
    private val history = DoubleArray(N)
    private var filled = 0
    private val fft = Fft(N)
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val mel = buildMelFilters()
    private val logMel = DoubleArray(MELS)
    private val cepSum = DoubleArray(CEPS)
    private val pitches = ArrayList<Double>()
    private var voiced = 0
    private var noiseFloor = 300.0

    @Synchronized
    fun addFrame(frame: ShortArray, len: Int) {
        val n = min(len, N)
        System.arraycopy(history, n, history, 0, N - n)
        var energy = 0.0
        for (i in 0 until n) {
            val s = frame[len - n + i].toDouble()
            history[N - n + i] = s
            energy += s * s
        }
        filled = min(N, filled + n)
        val rms = sqrt(energy / max(1, n))
        // Track the background noise level: falls quickly, rises slowly.
        noiseFloor = if (rms < noiseFloor) 0.9 * noiseFloor + 0.1 * rms else noiseFloor * 1.001
        noiseFloor = noiseFloor.coerceIn(30.0, 5000.0)
        if (filled < N || rms < noiseFloor * 2.5 || rms < 150) return

        voiced++
        addCepstrum()
        estimatePitch()?.let { pitches.add(it) }
    }

    @Synchronized
    fun voicedFrames(): Int = voiced

    /** Returns the voiceprint of everything heard since the last call, then resets. */
    @Synchronized
    fun take(): VoiceFeature? {
        val result = if (voiced >= MIN_VOICED_FRAMES) {
            val ceps = DoubleArray(CEPS) { cepSum[it] / voiced }
            val pitch = if (pitches.size >= 5) pitches.sorted()[pitches.size / 2] else 0.0
            VoiceFeature(ceps, pitch)
        } else null
        resetLocked()
        return result
    }

    @Synchronized
    fun reset() = resetLocked()

    private fun resetLocked() {
        cepSum.fill(0.0)
        pitches.clear()
        voiced = 0
    }

    private fun addCepstrum() {
        for (i in 0 until N) {
            re[i] = history[i] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        for (m in 0 until MELS) {
            var e = 0.0
            val (start, weights) = mel[m]
            for (k in weights.indices) {
                val bin = start + k
                e += weights[k] * (re[bin] * re[bin] + im[bin] * im[bin])
            }
            logMel[m] = ln(e + 1e-3)
        }
        val scale = sqrt(2.0 / MELS)
        for (c in 1..CEPS) {
            var sum = 0.0
            for (m in 0 until MELS) sum += logMel[m] * cos(PI * c * (m + 0.5) / MELS)
            cepSum[c - 1] += sum * scale
        }
    }

    /** Autocorrelation pitch estimate, 60–400 Hz. */
    private fun estimatePitch(): Double? {
        val minLag = sampleRate / 400
        val maxLag = sampleRate / 60
        var bestLag = -1
        var best = 0.0
        for (lag in minLag..maxLag) {
            var r = 0.0; var e1 = 0.0; var e2 = 0.0
            var i = 0
            while (i < N - lag) {
                val a = history[i]; val b = history[i + lag]
                r += a * b; e1 += a * a; e2 += b * b
                i++
            }
            val norm = r / sqrt(e1 * e2 + 1e-9)
            if (norm > best) {
                best = norm
                bestLag = lag
            }
        }
        return if (best > 0.5 && bestLag > 0) sampleRate.toDouble() / bestLag else null
    }

    private fun buildMelFilters(): List<Pair<Int, DoubleArray>> {
        fun hzToMel(f: Double) = 2595 * log10(1 + f / 700)
        fun melToHz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val lo = hzToMel(100.0)
        val hi = hzToMel(7000.0)
        val points = DoubleArray(MELS + 2) { melToHz(lo + (hi - lo) * it / (MELS + 1)) * N / sampleRate }
        return (0 until MELS).map { m ->
            val left = points[m]; val center = points[m + 1]; val right = points[m + 2]
            val start = left.toInt() + 1
            val end = min(N / 2, right.toInt())
            val weights = DoubleArray(max(0, end - start + 1)) { k ->
                val bin = (start + k).toDouble()
                when {
                    bin <= center -> (bin - left) / (center - left)
                    else -> (right - bin) / (right - center)
                }.coerceAtLeast(0.0)
            }
            start to weights
        }
    }
}

/** Online clustering of voiceprints into speakers. Main thread only. */
class SpeakerTracker {
    private class Profile(val id: Int, var ceps: DoubleArray, var pitch: Double, var count: Int)

    private val profiles = ArrayList<Profile>()
    private var nextId = 1
    var sensitivity = 0.5f

    fun clear() {
        profiles.clear()
        nextId = 1
    }

    /** @return speaker id and whether this is a new speaker. */
    fun assign(f: VoiceFeature): Pair<Int, Boolean> {
        val threshold = 2.4 - 1.6 * sensitivity
        val best = profiles.minByOrNull { distance(it, f) }
        if (best != null && (distance(best, f) < threshold || profiles.size >= 8)) {
            val alpha = max(0.15, 1.0 / (best.count + 1))
            for (i in best.ceps.indices) best.ceps[i] = (1 - alpha) * best.ceps[i] + alpha * f.ceps[i]
            if (f.pitchHz > 0) best.pitch = if (best.pitch > 0) (1 - alpha) * best.pitch + alpha * f.pitchHz else f.pitchHz
            best.count++
            return best.id to false
        }
        val p = Profile(nextId++, f.ceps.copyOf(), f.pitchHz, 1)
        profiles.add(p)
        return p.id to true
    }

    fun forget(id: Int) {
        profiles.removeAll { it.id == id }
    }

    private fun distance(p: Profile, f: VoiceFeature): Double {
        var d = 0.0
        for (i in p.ceps.indices) {
            val x = p.ceps[i] - f.ceps[i]
            d += x * x
        }
        val timbre = sqrt(d) / 1.5
        val pitch = if (p.pitch > 0 && f.pitchHz > 0) abs(ln(p.pitch / f.pitchHz)) / 0.15 else 0.5
        return sqrt(timbre * timbre + pitch * pitch)
    }
}

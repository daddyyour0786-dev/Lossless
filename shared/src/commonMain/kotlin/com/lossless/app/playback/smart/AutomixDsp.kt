/*
 * Lossless Automix — pure-Kotlin whole-track DSP measurement.
 *
 * No ML models, no native code: onset envelopes, autocorrelation tempo,
 * dynamic-programming beat tracking, and energy anchors, all on one
 * decimated mono stream.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3 or later.
 */

package com.lossless.app.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AutomixDsp {

    /** Set by tools (the synthetic harness, log taps) to hear the decisions. */
    var debugLog: ((String) -> Unit)? = null

    /** Working sample rate: enough for an 8 kHz nyquist, cheap enough for a phone. */
    private const val WORK_RATE = 11025.0

    /** STFT geometry: ~43 ms windows at ~86 fps. */
    private const val FFT_SIZE = 512
    private const val HOP = 128

    /** Per-frame adaptation of the whitening average: ~0.23 s memory at 86 fps. */
    private const val WHITENING_RATE = 0.02f

    /** Tempo search range. Anything outside is folded into it. */
    private const val MIN_TEMPO_BPM = 50.0
    const val MAX_TEMPO_BPM = 210.0

    /** Gaussian tempo prior: how hard the autocorrelation has to fight convention. */
    private const val PRIOR_CENTER_BPM = 120.0
    private const val PRIOR_WIDTH_BPM = 52.0

    /** Beat tracking accepts per-beat step sizes within these factors of the period. */
    private const val TRACK_MIN_FACTOR = 0.64
    private const val TRACK_MAX_FACTOR = 1.55

    /** Step-weight width: ln(delta/period) scaled by this before the Gaussian. */
    private const val TRACK_STEP_WIDTH = 0.08

    /** A level sample at or above this fraction of the track's loud reference counts as sounding. */
    private const val AUDIBLE_FRACTION = 0.03

    /** What the smoothed level has to reach, relative to the track's own smoothed peak, for the intro to be over. */
    private const val INTRO_FULL_FRACTION = 0.62

    /** The outro's sustained level ceiling, likewise. */
    private const val OUTRO_DROP_FRACTION = 0.62

    /** A beat grid with fewer usable beats than this is not evidence of a grid. */
    private const val MIN_BEATS = 16

    /** What one whole-track measurement produces. Mirrors the [TrackAnalysis] fields it fills. */
    class Result(
        val bpm: Double,
        val beatInterval: Double,
        val beatConfidence: Double,
        val beats: List<Double>,
        val downbeats: List<Double>,
        val phraseBoundaries: List<Double>,
        val firstBeat: Double,
        val audibleStartTime: Double,
        val pickupTime: Double,
        val introEndTime: Double,
        val outroStartTime: Double,
        val contentEndTime: Double,
        val mixInTime: Double,
        val mixOutTime: Double,
        val mixInCandidates: List<MixCandidate>,
        val mixOutCandidates: List<MixCandidate>,
        val energyCurve: List<EnergySample>,
        val lowEnergyCurve: List<EnergySample>,
    )

    private class Tempo(val bpm: Double, val periodFrames: Double, val confidence: Double)

    fun analyze(samples: FloatArray, sampleRate: Double, durationSeconds: Double): Result? {
        if (samples.isEmpty() || sampleRate <= 0) return null

        var pcm = samples
        var rate = sampleRate
        while (rate > WORK_RATE * 1.25) {
            val factor = if (rate > WORK_RATE * 2.5) 4 else 2
            pcm = decimate(pcm, factor)
            rate /= factor
        }
        val seconds = pcm.size / rate
        if (seconds < 8.0) return null

        var bands: Bands? = stft(pcm, rate)
        val working = bands ?: return null
        if (working.frames < 16) return null
        val fps = rate / HOP

        val kickFlux = bandFlux(working, 30.0, 180.0)
        val midFlux = bandFlux(working, 180.0, 2000.0)
        val highFlux = bandFlux(working, 2000.0, 8000.0)
        val rawKick = bandFlux(working, 30.0, 180.0, raw = true)
        val rawMid = bandFlux(working, 180.0, 2000.0, raw = true)
        val rawHigh = bandFlux(working, 2000.0, 8000.0, raw = true)
        bands = null

        val meanRadius = (fps * 0.7).roundToInt()
        subtractLocalMean(kickFlux, meanRadius)
        subtractLocalMean(midFlux, meanRadius)
        subtractLocalMean(highFlux, meanRadius)
        subtractLocalMean(rawKick, meanRadius)
        subtractLocalMean(rawMid, meanRadius)
        subtractLocalMean(rawHigh, meanRadius)
        normalizeToUnitMean(kickFlux)
        normalizeToUnitMean(midFlux)
        normalizeToUnitMean(highFlux)
        normalizeToUnitMean(rawKick)
        normalizeToUnitMean(rawMid)
        normalizeToUnitMean(rawHigh)

        val flux = FloatArray(kickFlux.size) { i -> 1.2f * kickFlux[i] + midFlux[i] + highFlux[i] }
        val lowFlux = kickFlux
        val rawFlux = FloatArray(rawKick.size) { i -> 1.2f * rawKick[i] + rawMid[i] + rawHigh[i] }
        val rawLowFlux = rawKick

        val energy = levelCurve(pcm, rate, lowpass = false)
        val lowEnergy = levelCurve(pcm, rate, lowpass = true)
        if (energy.isEmpty()) return null

        val loud = energy.maxOf { it.energy }
        if (loud <= 1e-9) return null // digital silence: one frame of equal level

        val audibleStart = firstAbove(energy, loud * AUDIBLE_FRACTION) ?: 0.0
        val contentEnd = lastAbove(energy, loud * AUDIBLE_FRACTION)
            ?: min(seconds, if (durationSeconds > 0) durationSeconds else seconds)

        val tempo = tempo(flux, fps) ?: tempoFromPulses(rawFlux, fps)
        var beats: List<Double>
        val bpm: Double
        val beatInterval: Double
        val beatConfidence: Double
        if (tempo != null) {
            val tail = if (seconds > 60.0) tempoOnSpan(flux, fps, max(0.0, seconds - 75.0), seconds) else null
            val chosen = if (tail != null && tail.confidence > tempo.confidence) tail else tempo
            var period = chosen.periodFrames
            beats = trackBeats(flux, rawFlux, rawLowFlux, fps, period, audibleStart, contentEnd)
            if (beats.size >= 2) period = (beats[1] - beats[0]) * fps
            val clean = gridCleanness(rawFlux, fps, beats, audibleStart, contentEnd)
            debugLog?.invoke("automix dsp: bpm=${chosen.bpm} beats=${beats.size} cleanness=$clean conf=${chosen.confidence}")
            if (beats.size >= MIN_BEATS && clean < 0.12) {
                val from = max(0.0, audibleStart * fps).roundToInt()
                val to = min(contentEnd * fps, rawLowFlux.size - 1.0).roundToInt()
                val lowPeaks = collectPeaks(rawLowFlux, from, to, period)
                val baseCoverage = gridCoverage(lowPeaks, beats, fps, period)
                var bestBeats = beats
                var bestCoverage = baseCoverage
                var bestPeriod = period
                var bestLabel = "base"
                val candidates = LinkedHashSet<Double>()
                val pulse = tempoFromPulses(rawFlux, fps)
                if (pulse != null) {
                    var pulsePeriod = pulse.periodFrames
                    var pulseBpm = 60.0 * fps / pulsePeriod
                    while (pulseBpm > MAX_TEMPO_BPM) { pulsePeriod *= 2; pulseBpm /= 2 }
                    while (pulseBpm < MIN_TEMPO_BPM) { pulsePeriod /= 2; pulseBpm *= 2 }
                    if (abs(pulsePeriod - period) > 0.05 * period) candidates += pulsePeriod
                }
                for (fold in doubleArrayOf(2.0, 0.5, 1.5, 0.75)) {
                    val candidate = period * fold
                    val candidateBpm = 60.0 * fps / candidate
                    if (candidateBpm in MIN_TEMPO_BPM..MAX_TEMPO_BPM) candidates += candidate
                }
                for (candidatePeriod in candidates) {
                    val trial = trackBeats(flux, rawFlux, rawLowFlux, fps, candidatePeriod, audibleStart, contentEnd)
                    if (trial.size < MIN_BEATS) continue
                    val trialCoverage = gridCoverage(lowPeaks, trial, fps, candidatePeriod)
                    debugLog?.invoke("  trial bpm=${60.0 * fps / candidatePeriod} coverage=$trialCoverage beats=${trial.size}")
                    if (trialCoverage > bestCoverage) {
                        bestCoverage = trialCoverage
                        bestBeats = trial
                        bestPeriod = (trial[1] - trial[0]) * fps
                        bestLabel = "${60.0 * fps / candidatePeriod}"
                    }
                }
                if (bestBeats != beats && bestCoverage >= max(0.35, baseCoverage + 0.10)) {
                    debugLog?.invoke("automix dsp: tempo recovery ${chosen.bpm} -> $bestLabel coverage $bestCoverage")
                    beats = bestBeats
                    period = bestPeriod
                }
            }
            bpm = 60.0 * fps / period
            beatInterval = period / fps
            beatConfidence = gridConfidence(rawFlux, fps, beats, chosen.confidence, audibleStart, contentEnd)
        } else {
            beats = emptyList()
            bpm = 0.0
            beatInterval = 0.0
            beatConfidence = 0.0
        }

        val downbeats = if (beats.size >= MIN_BEATS) findDownbeats(beats, flux, lowFlux, fps) else emptyList()
        val firstBeat = beats.firstOrNull() ?: 0.0
        val phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 4 == 0 }

        val pickup = pickupTime(flux, fps, audibleStart, contentEnd)

        val smoothed = smoothCurve(energy, (fps / 2).roundToInt())
        val smoothedLoud = smoothed.maxOfOrNull { it.energy } ?: loud
        val introEnd = firstAbove(smoothed, smoothedLoud * INTRO_FULL_FRACTION)?.let { max(audibleStart, it) }
            ?: min(contentEnd, audibleStart + 4.0)
        val outroStart = lastAbove(smoothed, smoothedLoud * OUTRO_DROP_FRACTION)
            ?.let { min(contentEnd, it + 1.0) }
            ?: max(introEnd, contentEnd - 8.0)

        val mixIn = if (introEnd > audibleStart) introEnd else firstBeat
        val mixOut = if (outroStart < contentEnd - 1.5) outroStart else max(mixIn, contentEnd - 4.0)

        return Result(
            bpm = bpm,
            beatInterval = beatInterval,
            beatConfidence = beatConfidence,
            beats = beats,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            firstBeat = firstBeat,
            audibleStartTime = audibleStart,
            pickupTime = pickup,
            introEndTime = introEnd,
            outroStartTime = outroStart,
            contentEndTime = contentEnd,
            mixInTime = mixIn,
            mixOutTime = mixOut,
            mixInCandidates = mixInCandidates(downbeats, mixIn, energy),
            mixOutCandidates = mixOutCandidates(downbeats, mixOut, contentEnd, energy),
            energyCurve = energy,
            lowEnergyCurve = lowEnergy,
        )
    }

    // ------------------------------------------------------------------ PCM

    private fun decimate(pcm: FloatArray, factor: Int): FloatArray {
        val out = FloatArray(pcm.size / factor)
        var write = 0
        var i = 0
        while (write < out.size) {
            var sum = 0f
            for (j in 0 until factor) sum += pcm[i + j]
            out[write++] = sum / factor
            i += factor
        }
        return out
    }

    // ------------------------------------------------------------------ STFT

    private class Bands(
        val frames: Int,
        val bins: Int,
        val rate: Double,
        val data: Array<FloatArray>,
        val rawData: Array<FloatArray>,
    )

    private fun stft(pcm: FloatArray, rate: Double): Bands {
        val n = FFT_SIZE
        val frames = (pcm.size - n) / HOP + 1
        if (frames <= 0) return Bands(0, n / 2 + 1, rate, emptyArray(), emptyArray())
        val bins = n / 2 + 1

        val window = FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat() }
        val real = FloatArray(n)
        val imag = FloatArray(n)
        val spectra = Array(frames) { FloatArray(bins) }
        val rawSpectra = Array(frames) { FloatArray(bins) }
        val average = FloatArray(bins)

        for (frame in 0 until frames) {
            val offset = frame * HOP
            for (i in 0 until n) {
                real[i] = pcm[offset + i] * window[i]
                imag[i] = 0f
            }
            fft(real, imag)
            val spectrum = spectra[frame]
            val raw = rawSpectra[frame]
            for (bin in 0 until bins) {
                val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                val log = ln(1.0f + 1000.0f * magnitude)
                raw[bin] = log
                average[bin] += WHITENING_RATE * (log - average[bin])
                spectrum[bin] = max(0f, log - average[bin])
            }
        }
        return Bands(frames, bins, rate, spectra, rawSpectra)
    }

    /** In-place radix-2 FFT; [imag] must be zeroed by the caller. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wReal = cos(angle).toFloat()
            val wImag = kotlin.math.sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = start + k + length / 2
                    val or = real[odd] * wr - imag[odd] * wr
                    val oi = real[odd] * wi - imag[odd] * wr
                    real[even] += or
                    imag[odd] += oi
                    val nextWr = wr * wReal - wi * wImag
                    wi = wr * wImag + wi * wReal
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
    }

    // -------------------------------------------------------------- envelopes

    private fun bandFlux(bands: Bands, fromHz: Double, toHz: Double, raw: Boolean = false): FloatArray {
        val hzPerBin = bands.rate / FFT_SIZE
        val fromBin = max(1, (fromHz / hzPerBin).toInt())
        val toBin = min(bands.bins - 1, (toHz / hzPerBin).toInt() + 1)
        val out = FloatArray(bands.frames)
        val source = if (raw) bands.rawData else bands.data
        var previous = FloatArray(0)
        for (frame in 0 until bands.frames) {
            val spectrum = source[frame]
            if (previous.isNotEmpty()) {
                var sum = 0f
                for (bin in fromBin..toBin) {
                    val diff = spectrum[bin] - previous[bin]
                    if (diff > 0f) sum += diff
                }
                out[frame] = sum
            }
            previous = spectrum
        }
        return out
    }

    /** Scales the envelope to mean 1 over the whole span, so bands combine on equal terms. */
    private fun normalizeToUnitMean(envelope: FloatArray) {
        var sum = 0f
        for (value in envelope) sum += value
        val mean = sum / max(1, envelope.size)
        if (mean <= 1e-9f) return
        for (i in envelope.indices) envelope[i] /= mean
    }

    /** Removes the moving average, so constant noise and sustain stop registering as onsets. */
    private fun subtractLocalMean(envelope: FloatArray, radiusFrames: Int) {
        val n = envelope.size
        if (n == 0) return
        val radius = max(1, radiusFrames)
        val out = FloatArray(n)
        var left = 0
        var right = -1
        var windowSum = 0f
        for (center in 0 until n) {
            val targetRight = min(n - 1, center + radius)
            val targetLeft = max(0, center - radius)
            while (right < targetRight) windowSum += envelope[++right]
            while (left < targetLeft) windowSum -= envelope[left++]
            out[center] = max(0f, envelope[center] - windowSum / (right - left + 1))
        }
        envelope.copyInto2(out)
    }

    private fun FloatArray.copyInto2(other: FloatArray) {
        other.copyInto(this, 0, 0, size)
    }

    /** RMS level once per tracker frame ([HOP] at analysis rate), optionally through a 150 Hz one-pole. */
    private fun levelCurve(pcm: FloatArray, rate: Double, lowpass: Boolean): List<EnergySample> {
        val alpha = if (lowpass) (6.28318530718 * 150.0 / (rate + 6.28318530718 * 150.0)).toFloat() else 0f
        val out = ArrayList<EnergySample>(pcm.size / HOP + 1)
        var lp = 0f
        var i = 0
        var frame = 0
        while (i + HOP <= pcm.size) {
            var sum = 0.0
            for (j in 0 until HOP) {
                var v = pcm[i + j]
                if (lowpass) {
                    lp += alpha * (v - lp)
                    v = lp
                }
                sum += v * v
            }
            val t = (frame.toDouble() * HOP + HOP / 2.0) / rate
            out += EnergySample(t, sqrt(sum / HOP))
            i += HOP
            frame++
        }
        return out
    }

    private fun smoothCurve(curve: List<EnergySample>, radiusFrames: Int): List<EnergySample> {
        val radius = max(1, radiusFrames)
        val out = ArrayList<EnergySample>(curve.size)
        for (i in curve.indices) {
            val from = max(0, i - radius)
            val to = min(curve.size - 1, i + radius)
            var sum = 0.0
            for (j in from..to) sum += curve[j].energy
            out += EnergySample(curve[i].time, sum / (to - from + 1))
        }
        return out
    }

    private fun firstAbove(curve: List<EnergySample>, level: Double): Double? =
        curve.firstOrNull { it.energy >= level }?.time

    private fun lastAbove(curve: List<EnergySample>, level: Double): Double? =
        curve.lastOrNull { it.energy >= level }?.time

    // ------------------------------------------------------------------ tempo

    private fun tempo(flux: FloatArray, fps: Double): Tempo? = tempoEnvelope(flux, 0, flux.size, fps)

    private fun tempoOnSpan(flux: FloatArray, fps: Double, fromSeconds: Double, toSeconds: Double): Tempo? {
        val from = (fromSeconds * fps).roundToInt().coerceIn(0, flux.size - 1)
        val to = (toSeconds * fps).roundToInt().coerceIn(from + 1, flux.size)
        if (to - from < fps * 8) return null
        return tempoEnvelope(flux, from, to, fps)
    }

    private fun tempoEnvelope(flux: FloatArray, from: Int, to: Int, fps: Double): Tempo? {
        val n = to - from
        val minLag = (fps * 60.0 / MAX_TEMPO_BPM).roundToInt().coerceAtLeast(2)
        val maxLag = (fps * 60.0 / MIN_TEMPO_BPM).roundToInt().coerceAtMost(n / 2)
        if (maxLag <= minLag) return null

        var mean = 0.0
        for (i in from until to) mean += flux[i]
        mean /= n

        val scores = DoubleArray(maxLag + 1)
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var scoreSum = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in from + lag until to) {
                sum += (flux[i] - mean) * (flux[i - lag] - mean)
            }
            sum /= n - lag
            val bpm = 60.0 * fps / lag
            val prior = exp(-(bpm - PRIOR_CENTER_BPM) * (bpm - PRIOR_CENTER_BPM) / (2 * PRIOR_WIDTH_BPM * PRIOR_WIDTH_BPM))
            val score = sum * (0.4 + 0.6 * prior)
            scores[lag] = score
            scoreSum += score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore <= 0) return null

        fun rawAt(targetLag: Int): Double {
            if (targetLag < minLag || targetLag > maxLag) return Double.NEGATIVE_INFINITY
            var sum = 0.0
            var count = 0
            for (i in from + targetLag until to) {
                sum += (flux[i] - mean) * (flux[i - targetLag] - mean)
                count++
            }
            return if (count > 0) sum / count else Double.NEGATIVE_INFINITY
        }
        val half = rawAt(bestLag / 2)
        val full = rawAt(bestLag)
        val chosen = if (bestLag % 2 == 0 && half > full * 0.92) bestLag / 2 else bestLag

        val refined = if (chosen in (minLag + 1) until maxLag) {
            val y1 = scores[chosen - 1]
            val y2 = scores[chosen]
            val y3 = scores[chosen + 1]
            val denom = y1 - 2 * y2 + y3
            if (abs(denom) > 1e-12) chosen - 0.5 * (y1 - y3) / denom else chosen.toDouble()
        } else {
            chosen.toDouble()
        }

        val avg = scoreSum / (maxLag - minLag + 1)
        val prominence = if (avg > 0) bestScore / avg else 1.0
        val confidence = ((prominence - 1.0) * 0.5).coerceIn(0.0, 0.96)

        return Tempo(60.0 * fps / refined, refined, confidence)
    }

    private fun tempoFromPulses(flux: FloatArray, fps: Double): Tempo? {
        val span = flux.size / fps
        if (span < 10) return null
        val nominal = fps * 0.11
        val peaks = collectPeaks(flux, 0, flux.size - 1, nominal)
        if (peaks.size < MIN_BEATS) return null
        val buckets = HashMap<Int, Int>()
        var bestLag = 0
        var bestCount = 0
        for (i in 1 until peaks.size) {
            val gap = peaks[i].first - peaks[i - 1].first
            for (folds in 1..4) {
                val bpm = 60.0 * fps * folds / gap
                if (bpm in MIN_TEMPO_BPM..MAX_TEMPO_BPM) {
                    val lag = (fps * 60.0 / bpm).roundToInt()
                    val count = (buckets[lag] ?: 0) + 1
                    buckets[lag] = count
                    if (count > bestCount) {
                        bestCount = count
                        bestLag = lag
                    }
                    break
                }
            }
        }
        if (bestLag == 0 || bestCount < 8) return null
        return Tempo(60.0 * fps / bestLag, bestLag.toDouble(), 0.25)
    }

    // ---------------------------------------------------------------- grid: dp

    private fun stepWeight(delta: Double, period: Double): Double {
        val x = ln(delta / period) / TRACK_STEP_WIDTH
        return exp(-0.5 * x * x)
    }

    private fun trackBeats(
        flux: FloatArray,
        rawFlux: FloatArray,
        rawLowFlux: FloatArray,
        fps: Double,
        periodFrames: Double,
        audibleStart: Double,
        contentEnd: Double,
    ): List<Double> {
        val nominalPeriod = periodFrames
        val fromFrame = max(0.0, audibleStart * fps)
        val toFrame = min(contentEnd * fps, flux.size - 1.0)
        val from = fromFrame.roundToInt()
        val to = toFrame.roundToInt()
        if (to - from < nominalPeriod * 4) return emptyList()

        val minDelta = max(2, (nominalPeriod * TRACK_MIN_FACTOR).roundToInt())
        val maxDelta = (nominalPeriod * TRACK_MAX_FACTOR).roundToInt()

        val best = DoubleArray(to + 1) { Double.NEGATIVE_INFINITY }
        val parent = IntArray(to + 1) { -1 }
        if (from >= to) return emptyList()
        best[from] = flux[from].toDouble()
        val weights = DoubleArray(maxDelta + 1) { d -> if (d >= minDelta) stepWeight(d.toDouble(), nominalPeriod) else 0.0 }
        for (i in from + 1..to) {
            var localBest = Double.NEGATIVE_INFINITY
            var localParent = -1
            val low = max(from, i - maxDelta)
            val high = i - minDelta
            for (p in low..high) {
                val d = i - p
                val score = best[p] + weights[d]
                if (score > localBest) {
                    localBest = score
                    localParent = p
                }
            }
            if (localParent >= 0) {
                best[i] = localBest + flux[i]
                parent[i] = localParent
            }
        }

        var end = to
        for (i in to downTo max(from + 1, (to - nominalPeriod).roundToInt())) {
            if (best[i] > best[end]) end = i
        }
        if (best[end] == Double.NEGATIVE_INFINITY) return emptyList()

        val path = ArrayList<Int>()
        var cursor = end
        while (cursor >= from) {
            path += cursor
            val next = parent[cursor]
            if (next < 0) break
            cursor = next
        }
        path.reverse()
        if (path.size < MIN_BEATS / 2) return emptyList()

        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        val count = path.size
        for (index in 0 until count) {
            val t = path[index].toDouble()
            sumX += index
            sumY += t
            sumXX += index.toDouble() * index
            sumXY += index * t
        }
        val denom = count * sumXX - sumX * sumX
        if (abs(denom) < 1e-9) return emptyList()
        var slope = (count * sumXY - sumX * sumY) / denom
        var intercept = (sumY - slope * sumX) / count

        val refined = refineGrid(
            rawFlux,
            rawLowFlux,
            fps,
            slope,
            intercept,
            fromFrame,
            toFrame,
        )
        slope = refined.first
        val phase = refined.second

        val beats = ArrayList<Double>()
        var k = ceil((fromFrame - phase) / slope).toInt()
        while (true) {
            val f = phase + k * slope
            if (f > toFrame + slope * 0.5) break
            if (f >= fromFrame - slope * 0.5) beats += f / fps
            k++
        }
        return beats
    }

    // ----------------------------------------------------- grid: pulse refit

    private fun collectPeaks(
        flux: FloatArray,
        lo: Int,
        hi: Int,
        periodFrames: Double,
    ): List<Pair<Double, Double>> {
        var mean = 0.0
        for (i in lo..hi) mean += flux[i]
        mean /= max(1, hi - lo + 1)
        val refractory = periodFrames * 0.55
        val peaks = ArrayList<Pair<Double, Double>>()
        var i = max(1, lo)
        var lastAccepted = -refractory - 1
        // flux[i + 1] is read below, so the final frame cannot be examined —
        // tempoFromPulses() passes hi = flux.size - 1.
        val last = min(hi, flux.size - 2)
        while (i <= last) {
            if (flux[i] > mean && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1] &&
                i - lastAccepted >= refractory
            ) {
                var left = i
                var right = i
                while (left > 1 && flux[left - 1] >= flux[i] * 0.7f && i - left < 3) left--
                while (right < hi && flux[right + 1] >= flux[i] * 0.7f && right - i < 3) right++
                var weightedSum = 0.0
                var totalWeight = 0.0
                for (j in left..right) {
                    val w = (flux[j] * flux[j]).toDouble()
                    weightedSum += j * w
                    totalWeight += w
                }
                peaks += (if (totalWeight > 0) weightedSum / totalWeight else i.toDouble()) to flux[i].toDouble()
                lastAccepted = i.toDouble()
            }
            i++
        }
        return peaks
    }

    private fun refineGrid(
        flux: FloatArray,
        lowFlux: FloatArray,
        fps: Double,
        slope0: Double,
        phase0: Double,
        fromFrame: Double,
        toFrame: Double,
    ): Pair<Double, Double> {
        if (toFrame <= fromFrame + slope0 * MIN_BEATS) return slope0 to phase0
        val lo = fromFrame.toInt().coerceAtLeast(0)
        val hi = toFrame.toInt().coerceAtMost(flux.size - 2)
        if (hi <= lo + 2) return slope0 to phase0

        val peaks = collectPeaks(flux, lo, hi, slope0)
        val phasePeaks = collectPeaks(lowFlux, lo, hi, slope0)
        if (peaks.size < MIN_BEATS / 2) return slope0 to phase0

        val estimates = ArrayList<Double>()
        val weights = ArrayList<Double>()
        for (j in 1 until peaks.size) {
            val gap = peaks[j].first - peaks[j - 1].first
            val folds = max(1, ((gap / slope0).roundToInt()))
            val estimate = gap / folds
            if (estimate in slope0 * 0.9..slope0 * 1.1) {
                estimates += estimate
                weights += min(peaks[j].second, peaks[j - 1].second)
            }
        }
        if (estimates.size < 8) return slope0 to phase0

        var weightedSum = 0.0
        var weightTotal = 0.0
        var period = slope0
        for (j in estimates.indices) {
            if (abs(estimates[j] - slope0) > 0.025 * slope0) continue
            weightedSum += estimates[j] * weights[j]
            weightTotal += weights[j]
        }
        if (weightTotal > 0) period = weightedSum / weightTotal
        if (abs(period - slope0) > 0.03 * slope0) return slope0 to phase0

        val anchorFull = if (phasePeaks.size >= 8) phasePeaks else peaks
        val anchorMax = anchorFull.maxOf { it.second }
        val anchor = anchorFull.filter { it.second >= anchorMax * 0.35 }
        if (anchor.size < 8) return period to phase0

        var sumWeight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for ((position, height) in anchor) {
            val index = ((position - phase0) / period).roundToInt().toDouble()
            if (abs(position - phase0 - index * period) > 0.3 * period) continue
            sumWeight += height
            sumX += height * index
            sumY += height * position
            sumXX += height * index * index
            sumXY += height * index * position
        }
        val denominator = sumWeight * sumXX - sumX * sumX
        if (sumWeight <= 0 || abs(denominator) < 1e-9) return period to phase0
        val fitPeriod = (sumWeight * sumXY - sumX * sumY) / denominator
        val fitPhase = (sumY - fitPeriod * sumX) / sumWeight
        if (abs(fitPeriod - period) > 0.05 * period) return period to phase0
        debugLog?.invoke(
            "refine: ioi=${(period * 100).roundToInt() / 100.0}f fit=${(fitPeriod * 100).roundToInt() / 100.0}f " +
                "phase=${(fitPhase * 100).roundToInt() / 100.0}f anchor=${anchor.size} low=${phasePeaks.size}",
        )
        return fitPeriod to fitPhase
    }

    // ----------------------------------------------------------- grid scores

    private fun gridCleanness(
        flux: FloatArray,
        fps: Double,
        beats: List<Double>,
        audibleStart: Double,
        contentEnd: Double,
    ): Double {
        if (beats.size < MIN_BEATS / 2) return 0.0
        val from = beats.indexOfFirst { it >= audibleStart }
        val to = beats.indexOfLast { it <= contentEnd }
        if (to - from < MIN_BEATS) return 0.0
        val interval = measureInterval(beats, from, to)
        if (interval <= 0) return 0.0
        var onSum = 0.0
        var onCount = 0
        var offSum = 0.0
        var offCount = 0
        for (i in from..to) {
            val frame = (beats[i] * fps).roundToInt()
            if (frame in 0 until flux.size) {
                onSum += flux[frame]
                onCount++
                val offFrame = frame + (interval * fps / 2).roundToInt()
                if (offFrame in 0 until flux.size) {
                    offSum += flux[offFrame]
                    offCount++
                }
            }
        }
        if (onCount == 0 || offCount == 0) return 0.0
        val on = onSum / onCount
        val off = offSum / offCount
        if (on + off < 1e-6) return 0.0
        return ((on - off) / (on + off)).coerceIn(-1.0, 1.0)
    }

    private fun gridCoverage(
        peaks: List<Pair<Double, Double>>,
        beats: List<Double>,
        fps: Double,
        periodFrames: Double,
    ): Double {
        if (peaks.size < MIN_BEATS / 2 || beats.size < MIN_BEATS / 2) return 0.0
        val top = peaks.maxOf { it.second }
        val strong = peaks.filter { it.second >= top * 0.25 }
        if (strong.isEmpty()) return 0.0
        val tolerance = periodFrames * 0.12
        var hits = 0
        var count = 0
        var p = 0
        for (beat in beats) {
            val frame = beat * fps
            while (p < strong.size && strong[p].first < frame - tolerance) p++
            var nearest = Double.MAX_VALUE
            var q = p
            while (q < strong.size && strong[q].first <= frame + tolerance) {
                nearest = min(nearest, abs(strong[q].first - frame))
                q++
            }
            if (nearest <= tolerance) hits++
            count++
        }
        return if (count > 0) hits.toDouble() / count else 0.0
    }

    private fun gridConfidence(
        flux: FloatArray,
        fps: Double,
        beats: List<Double>,
        tempoConfidence: Double,
        audibleStart: Double,
        contentEnd: Double,
    ): Double {
        if (beats.size < MIN_BEATS || tempoConfidence <= 0) return 0.0
        val cleanness = gridCleanness(flux, fps, beats, audibleStart, contentEnd)
        val gridScore = (cleanness / 0.30).coerceIn(0.0, 1.0)
        return (tempoConfidence * (0.4 + 0.6 * gridScore) * 0.98).coerceIn(0.0, 0.95)
    }

    private fun measureInterval(beats: List<Double>, from: Int, to: Int): Double =
        if (to - from >= 4) (beats[to] - beats[from]) / (to - from) else 0.0

    // ------------------------------------------------------------- downbeats

    private fun findDownbeats(
        beats: List<Double>,
        flux: FloatArray,
        lowFlux: FloatArray,
        fps: Double,
    ): List<Double> {
        if (beats.size < 8) return emptyList()
        var bestPhase = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0..3) {
            var score = 0.0
            var count = 0
            var i = phase
            while (i < beats.size) {
                val frame = (beats[i] * fps).roundToInt()
                if (frame in 0 until flux.size) {
                    score += flux[frame] + 0.5 * lowFlux[frame]
                    count++
                }
                i += 4
            }
            if (count > 0 && score / count > bestScore) {
                bestScore = score / count
                bestPhase = phase
            }
        }
        val out = ArrayList<Double>()
        var i = bestPhase
        while (i < beats.size) {
            out += beats[i]
            i += 4
        }
        return out
    }

    // ---------------------------------------------------------------- anchors

    private fun pickupTime(flux: FloatArray, fps: Double, audibleStart: Double, contentEnd: Double): Double {
        val from = (audibleStart * fps).roundToInt().coerceAtLeast(0)
        val to = (contentEnd * fps).roundToInt().coerceIn(from + 1, flux.size)
        var mean = 0.0
        for (i in from until to) mean += flux[i]
        mean /= (to - from)
        for (i in from until to) {
            if (flux[i] > mean * 1.6) return i / fps
        }
        return audibleStart
    }

    private fun mixInCandidates(downbeats: List<Double>, mixIn: Double, energy: List<EnergySample>): List<MixCandidate> {
        if (downbeats.isEmpty()) {
            return listOf(MixCandidate(mixIn, 1.0, "intro-end"))
        }
        return downbeats
            .filter { it >= mixIn - 1.0 && it <= mixIn + 16.0 }
            .take(6)
            .mapIndexed { index, time -> MixCandidate(time, 1.0 - index * 0.12, "downbeat") }
    }

    private fun mixOutCandidates(
        downbeats: List<Double>,
        mixOut: Double,
        contentEnd: Double,
        energy: List<EnergySample>,
    ): List<MixCandidate> {
        if (downbeats.isEmpty()) {
            return listOf(MixCandidate(mixOut, 1.0, "outro-start"))
        }
        return downbeats
            .filter { it >= mixOut - 4.0 && it <= contentEnd - 1.5 }
            .takeLast(8)
            .reversed()
            .mapIndexed { index, time -> MixCandidate(time, 1.0 - index * 0.1, "downbeat") }
    }
}

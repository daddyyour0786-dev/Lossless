package com.lossless.app.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AutomixPlanner {

    const val BEATMATCH_CONFIDENCE = 0.55

    private const val SYNC_TEMPO_WINDOW = 0.03
    private const val STRETCH_TEMPO_WINDOW = 0.10
    private const val MAX_OVERLAP_BEATS = 16
    private const val MIN_OVERLAP_BEATS = 4
    private const val MIN_OVERLAP_SECONDS = 3.5
    private const val MAX_OVERLAP_SECONDS = 14.0
    private const val CONTENT_GUARDRAIL_SECONDS = 1.7
    private const val PLAIN_FADE_SECONDS = 4.0
    private const val CUE_SKIP_CAP_FRACTION = 0.25

    class Input(
        val outgoing: TrackAnalysis?,
        val incoming: TrackAnalysis?,
        val outgoingDurationSeconds: Double,
        val incomingDurationSeconds: Double,
        val currentTimeSeconds: Double = 0.0,
    )

    fun plan(input: Input): TransitionPlan? {
        val outgoing = input.outgoing
        val incoming = input.incoming
        if (incoming == null) return null

        val outMeasures = outgoing?.measuredEnough() == true
        val inMeasures = incoming.measuredEnough()
        if (!outMeasures || !inMeasures) return plainFade(input, outgoing, incoming)

        val out = outgoing!!
        val outContent = contentEndOf(out, input.outgoingDurationSeconds)
        val inGrid = incoming.beatGrid()
        val outGrid = out.beatGrid()
        if (inGrid.isEmpty() || outGrid.isEmpty()) return plainFade(input, out, incoming)

        val outInterval = out.beatInterval
        val inIntervalRaw = incoming.beatInterval
        val inInterval = foldInterval(inIntervalRaw, outInterval)
        val ratio = outInterval / inInterval
        val delta = abs(ratio - 1.0)

        val beatmatched = out.beatConfidence >= BEATMATCH_CONFIDENCE &&
            incoming.beatConfidence >= BEATMATCH_CONFIDENCE

        var overlapBeats = MAX_OVERLAP_BEATS
        var overlapSeconds = overlapBeats * outInterval
        while (overlapBeats > MIN_OVERLAP_BEATS &&
            (overlapSeconds > MAX_OVERLAP_SECONDS || overlapSeconds < MIN_OVERLAP_SECONDS)
        ) {
            overlapBeats /= 2
            overlapSeconds = overlapBeats * outInterval
        }
        overlapSeconds = overlapSeconds.coerceIn(MIN_OVERLAP_SECONDS, MAX_OVERLAP_SECONDS)

        val outgoingRate: Double
        val incomingRate: Double
        when {
            delta <= SYNC_TEMPO_WINDOW -> {
                outgoingRate = 1.0
                incomingRate = 1.0
            }
            delta <= STRETCH_TEMPO_WINDOW && beatmatched -> {
                if (ratio > 1.0) {
                    outgoingRate = ratio.coerceAtMost(1.0 + STRETCH_TEMPO_WINDOW)
                    incomingRate = 1.0
                } else {
                    outgoingRate = 1.0
                    incomingRate = (1.0 / ratio).coerceAtMost(1.0 + STRETCH_TEMPO_WINDOW)
                }
            }
            else -> {
                outgoingRate = 1.0
                incomingRate = 1.0
            }
        }
        val stretch = outgoingRate != 1.0 || incomingRate != 1.0
        val far = delta > STRETCH_TEMPO_WINDOW
        val style = when {
            far -> TransitionStyle.DJ_FILTER
            !beatmatched -> TransitionStyle.EQUAL_POWER
            else -> TransitionStyle.DJ_BLEND
        }

        val outDownbeats = out.downbeats.ifEmpty { out.beatsList() }
        val guardEnd = outContent - CONTENT_GUARDRAIL_SECONDS
        val latestStart = guardEnd - overlapSeconds
        if (latestStart <= 0) return plainFade(input, out, incoming)
        val mixOutAnchor = out.mixOutCandidates.firstOrNull()?.time ?: out.mixOutTime
        var start = min(if (mixOutAnchor > 0) mixOutAnchor else guardEnd, guardEnd) - overlapSeconds
        if (start > latestStart) start = latestStart
        start = snapDown(outDownbeats, start)
        val end = start + overlapSeconds

        val mediaRatio = incomingRate / outgoingRate
        val inDownbeats = incoming.downbeats.ifEmpty { incoming.beatsList() }
        val drop = incoming.mixInCandidates.firstOrNull()?.time
            ?: (if (incoming.introEndTime > 0) incoming.introEndTime else incoming.firstBeat)
        var cue = drop - overlapSeconds * mediaRatio
        cue = snapDown(inDownbeats, cue)
        val cueCap = input.incomingDurationSeconds * CUE_SKIP_CAP_FRACTION
        if (cue > cueCap) cue = snapDown(inDownbeats, cueCap)
        cue = max(0.0, cue)
        val handoff = cue + overlapSeconds * mediaRatio

        return TransitionPlan(
            shouldStart = input.currentTimeSeconds >= start,
            markerVisible = style != TransitionStyle.EQUAL_POWER,
            reason = "automix",
            transitionStart = start,
            transitionEnd = end,
            fadeSeconds = overlapSeconds,
            transitionStyle = style,
            incomingCueTime = cue,
            incomingHandoffTime = handoff,
            incomingPlaybackRate = incomingRate,
            outgoingPlaybackRate = outgoingRate,
            transitionBeats = overlapBeats,
            bassSwap = style == TransitionStyle.DJ_BLEND,
            outgoingBpm = 60.0 / outInterval,
            incomingBpm = 60.0 / inInterval,
            beatSeconds = outInterval,
            phaseLock = style == TransitionStyle.DJ_BLEND && !stretch,
            filterVariant = FilterTransitionVariant.SWEEP,
            // The renderer scales the whole ride by this (CrossfadeController
            // ~:3126); leaving it at the default 0.0 made the far-tempo tier a
            // plain crossfade.
            filterSweep = if (far) FILTER_SWEEP else 0.0,
            policyReasons = buildList {
                if (far) add("tempo-gap-over-10%")
                else if (!beatmatched) add("grid-confidence-below-0.55")
                else if (!stretch) add("tempo-in-sync")
                else add("tempo-via-stretch")
            },
        )
    }

    private fun plainFade(input: Input, outgoing: TrackAnalysis?, incoming: TrackAnalysis): TransitionPlan {
        val content = if (outgoing != null) contentEndOf(outgoing, input.outgoingDurationSeconds) else input.outgoingDurationSeconds
        val end = max(PLAIN_FADE_SECONDS, content - CONTENT_GUARDRAIL_SECONDS)
        val start = max(0.0, end - PLAIN_FADE_SECONDS)
        val cue = incoming.audibleStartTime ?: 0.0
        return TransitionPlan(
            shouldStart = input.currentTimeSeconds >= start,
            markerVisible = false,
            reason = "automix-plain",
            transitionStart = start,
            transitionEnd = end,
            fadeSeconds = PLAIN_FADE_SECONDS,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = cue,
            incomingHandoffTime = cue + PLAIN_FADE_SECONDS,
            policyReasons = listOf("unmeasured-pair-plain-fade"),
        )
    }

    private fun TrackAnalysis.measuredEnough(): Boolean =
        bpm > MIN_BPM_WORK && beatInterval > 0 && beatGrid().size >= MIN_GRID_BEATS

    private fun TrackAnalysis.beatGrid(): List<Double> =
        when {
            tailBeats != null && headBeats != null -> headBeats.beats + tailBeats.beats.filter { it > headBeats.beats.last() }
            tailBeats != null -> tailBeats.beats
            headBeats != null -> headBeats.beats
            else -> emptyList()
        }

    private fun TrackAnalysis.beatsList(): List<Double> = beatGrid()

    private fun contentEndOf(analysis: TrackAnalysis, duration: Double): Double =
        if (analysis.contentEndTime > 0) analysis.contentEndTime else duration

    private fun snapDown(grid: List<Double>, t: Double): Double {
        if (grid.isEmpty()) return t
        var candidate = grid.first()
        for (point in grid) {
            if (point <= t) candidate = point else break
        }
        return if (t < grid.first()) t else candidate
    }

    private fun foldInterval(interval: Double, reference: Double): Double {
        if (interval <= 0 || reference <= 0) return interval
        var folded = interval
        while (folded / reference > 2.0 / 1.5) folded /= 2
        while (reference / folded > 2.0 / 1.5) folded *= 2
        return folded
    }

    private const val MIN_BPM_WORK = 40.0
    private const val MIN_GRID_BEATS = 16
}

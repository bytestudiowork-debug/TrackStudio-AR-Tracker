package com.trackstudio.artracker

import org.junit.Assert.*
import org.junit.Test

class CameraCaptureProfileSelectorTest {
    @Test fun prefersLargestAnalysisStreamThatMeetsThirtyFpsBudget() {
        val selected = CameraCaptureProfileSelector.chooseAnalysisStream(listOf(
            CameraStreamCandidate(640, 480, 33_333_333L),
            CameraStreamCandidate(320, 240, 16_666_667L),
            CameraStreamCandidate(640, 480, 50_000_000L)
        ))
        assertEquals(640, selected?.width)
        assertEquals(480, selected?.height)
    }

    @Test fun fallsBackToFasterSmallerStreamWhenLargeStreamIsTooSlow() {
        val selected = CameraCaptureProfileSelector.chooseAnalysisStream(listOf(
            CameraStreamCandidate(640, 480, 66_666_667L),
            CameraStreamCandidate(320, 240, 33_333_333L)
        ))
        assertEquals(320, selected?.width)
        assertEquals(240, selected?.height)
    }

    @Test fun treatsUnreportedMinimumDurationAsUnknownRatherThanImpossible() {
        val selected = CameraCaptureProfileSelector.chooseAnalysisStream(listOf(
            CameraStreamCandidate(640, 480, 0L),
            CameraStreamCandidate(320, 240, 16_666_667L)
        ))
        assertEquals(640, selected?.width)
    }

    @Test fun choosesHighestSupportedAeRangeCappedAtThirtyFps() {
        val selected = CameraCaptureProfileSelector.chooseFpsRange(listOf(
            CameraFpsRangeCandidate(15, 30),
            CameraFpsRangeCandidate(30, 30),
            CameraFpsRangeCandidate(30, 60)
        ))
        assertEquals(CameraFpsRangeCandidate(30, 30), selected)
    }

    @Test fun returnsNullWhenNoUsableStreamOrFpsRangeExists() {
        assertNull(CameraCaptureProfileSelector.chooseAnalysisStream(emptyList()))
        assertNull(CameraCaptureProfileSelector.chooseFpsRange(listOf(
            CameraFpsRangeCandidate(30, 60),
            CameraFpsRangeCandidate(0, 30)
        )))
    }
}

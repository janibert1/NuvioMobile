package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSubtitleCueParserRangesTest {

    @Test
    fun parsesSrtStartAndEndTimes() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,400
            First line.

            2
            00:00:03,000 --> 00:00:03,800
            Second line.
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseWithRanges(srt, sourceUrl = "sub.srt")

        assertEquals(
            listOf(
                SubtitleAlignmentCue(1_000, 2_400),
                SubtitleAlignmentCue(3_000, 3_800),
            ),
            cues,
        )
    }

    @Test
    fun parsesWebVttStartAndEndTimes() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.400
            First line.

            00:00:03.000 --> 00:00:03.800
            Second line.
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseWithRanges(vtt, sourceUrl = "sub.vtt")

        assertEquals(
            listOf(
                SubtitleAlignmentCue(1_000, 2_400),
                SubtitleAlignmentCue(3_000, 3_800),
            ),
            cues,
        )
    }

    @Test
    fun parsesAssStartAndEndTimes() {
        val ass = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.40,Default,,0,0,0,,First line.
            Dialogue: 0,0:00:03.00,0:00:03.80,Default,,0,0,0,,Second line.
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseWithRanges(ass, sourceUrl = "sub.ass")

        assertEquals(
            listOf(
                SubtitleAlignmentCue(1_000, 2_400),
                SubtitleAlignmentCue(3_000, 3_800),
            ),
            cues,
        )
    }

    @Test
    fun parsesTtmlStartAndEndTimes() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:01.000" end="00:00:02.400">First line.</p>
                  <p begin="00:00:03.000" end="00:00:03.800">Second line.</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseWithRanges(ttml, sourceUrl = "sub.ttml")

        assertEquals(
            listOf(
                SubtitleAlignmentCue(1_000, 2_400),
                SubtitleAlignmentCue(3_000, 3_800),
            ),
            cues,
        )
    }

    @Test
    fun ttmlFallsBackToDurationWhenNoEndAttribute() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:01.000" dur="1.400s">First line.</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseWithRanges(ttml, sourceUrl = "sub.ttml")

        assertEquals(listOf(SubtitleAlignmentCue(1_000, 2_400)), cues)
    }

    @Test
    fun skipsMalformedCuesWithoutEndTime() {
        val srt = """
            1
            00:00:01,000 --> not-a-timestamp
            Bad line.

            2
            00:00:03,000 --> 00:00:03,800
            Good line.
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseWithRanges(srt, sourceUrl = "sub.srt")

        assertEquals(listOf(SubtitleAlignmentCue(3_000, 3_800)), cues)
    }

    @Test
    fun emptyInputReturnsEmptyList() {
        assertEquals(emptyList(), PlayerSubtitleCueParser.parseWithRanges("", sourceUrl = "sub.srt"))
    }
}

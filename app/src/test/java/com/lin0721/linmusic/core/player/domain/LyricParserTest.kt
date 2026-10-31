package com.lin0721.linmusic.core.player.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {

    // ======================= LRC 解析 =======================

    @Test
    fun `解析标准两位毫秒的LRC行`() {
        val lines = LyricParser.parseLrc("[00:12.34]Hello world")
        assertEquals(1, lines.size)
        assertEquals(12340L, lines[0].timeMs) // 12s + 34*10ms
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun `解析三位毫秒的LRC行`() {
        val lines = LyricParser.parseLrc("[00:12.340]Hello world")
        assertEquals(1, lines.size)
        assertEquals(12340L, lines[0].timeMs)
    }

    @Test
    fun `LRC支持冒号或点号作为毫秒分隔符`() {
        val lines = LyricParser.parseLrc("[01:02:500]Colon separated")
        assertEquals(1, lines.size)
        assertEquals(62500L, lines[0].timeMs) // 1*60000 + 2*1000 + 500
    }

    @Test
    fun `LRC多行按时间戳排序`() {
        val text = """
            [00:20.00]Second
            [00:10.00]First
        """.trimIndent()
        val lines = LyricParser.parseLrc(text)
        assertEquals(2, lines.size)
        assertEquals("First", lines[0].text)
        assertEquals("Second", lines[1].text)
    }

    @Test
    fun `LRC空文本行被跳过`() {
        val text = "[00:10.00]\n[00:20.00]Real line"
        val lines = LyricParser.parseLrc(text)
        assertEquals(1, lines.size)
        assertEquals("Real line", lines[0].text)
    }

    @Test
    fun `LRC不匹配格式的行被忽略`() {
        val text = "not a lyric line\n[00:10.00]Valid line"
        val lines = LyricParser.parseLrc(text)
        assertEquals(1, lines.size)
        assertEquals("Valid line", lines[0].text)
    }

    @Test
    fun `LRC空字符串返回空列表`() {
        assertTrue(LyricParser.parseLrc("").isEmpty())
    }

    // ======================= YRC 逐字解析 =======================

    @Test
    fun `解析单行YRC并计算逐字相对偏移`() {
        val yrc = "[0,3000](0,500,0)Hello(500,500,0)World"
        val lines = LyricParser.parseYrc(yrc)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(0L, line.timeMs)
        assertEquals(3000L, line.durationMs)
        assertEquals("HelloWorld", line.text)
        assertEquals(2, line.words.size)
        assertEquals("Hello", line.words[0].text)
        assertEquals(0L, line.words[0].startOffsetMs)
        assertEquals(500L, line.words[0].durationMs)
        assertEquals("World", line.words[1].text)
        assertEquals(500L, line.words[1].startOffsetMs) // 500(绝对时间) - 0(行起始时间)
    }

    @Test
    fun `YRC单字起始时间相对行起始时间做偏移换算`() {
        // 行起始时间非0时，字词的相对偏移应为 绝对时间-行起始时间
        val yrc = "[1000,2000](1000,300,0)Word1(1300,300,0)Word2"
        val lines = LyricParser.parseYrc(yrc)
        assertEquals(1, lines.size)
        assertEquals(0L, lines[0].words[0].startOffsetMs)
        assertEquals(300L, lines[0].words[1].startOffsetMs)
    }

    @Test
    fun `YRC多行按时间戳排序`() {
        val yrc = "[2000,1000](2000,500,0)Second\n[0,1000](0,500,0)First"
        val lines = LyricParser.parseYrc(yrc)
        assertEquals(2, lines.size)
        assertEquals("First", lines[0].text)
        assertEquals("Second", lines[1].text)
    }

    @Test
    fun `YRC空白行与不匹配行被跳过`() {
        val yrc = "\n   \nnot a yrc line\n[0,1000](0,500,0)Valid"
        val lines = LyricParser.parseYrc(yrc)
        assertEquals(1, lines.size)
        assertEquals("Valid", lines[0].text)
    }

    @Test
    fun `YRC空字符串返回空列表`() {
        assertTrue(LyricParser.parseYrc("").isEmpty())
    }

    @Test
    fun `修复英文歌词中音节间隙错误如securi_ty`() {
        assertEquals("security", LyricParser.fixWordGaps("securi ty"))
        assertEquals("possible", LyricParser.fixWordGaps("possi ble"))
        assertEquals("together forever", LyricParser.fixWordGaps("to gether for ever"))
        assertEquals("mama", LyricParser.fixWordGaps("ma ma"))
        assertEquals("papa", LyricParser.fixWordGaps("pa pa"))
        assertEquals("baby", LyricParser.fixWordGaps("ba by"))
        assertEquals("together", LyricParser.fixWordGaps("to ge ther"))
        assertEquals("running", LyricParser.fixWordGaps("run ning"))
        assertEquals("feeling", LyricParser.fixWordGaps("feel ing"))
        assertEquals("can't breathe", LyricParser.fixWordGaps("can 't breathe"))
        assertEquals("beautiful lady", LyricParser.fixWordGaps("beau ti ful la dy"))
    }

    @Test
    fun `stitchSyllableGaps缝合mama与多音节`() {
        val words = listOf(
            WordInfo("ma ", 0L, 200L),
            WordInfo("ma ", 200L, 300L)
        )
        val stitched = LyricParser.stitchSyllableGaps(words)
        assertEquals("ma", stitched[0].text)
        assertEquals("ma ", stitched[1].text)

        val words3 = listOf(
            WordInfo("to ", 0L, 100L),
            WordInfo("ge ", 100L, 100L),
            WordInfo("ther ", 200L, 200L)
        )
        val stitched3 = LyricParser.stitchSyllableGaps(words3)
        assertEquals("to", stitched3[0].text)
        assertEquals("ge", stitched3[1].text)
        assertEquals("ther ", stitched3[2].text)
    }

    @Test
    fun `TTML分词音节间隙无缝缝合`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:03.000">
                    <span begin="00:01.000" end="00:01.500">securi</span>
                    <span begin="00:01.500" end="00:02.000">ty </span>
                    <span begin="00:02.000" end="00:03.000">guard</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
        val lines = LyricParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("security guard", lines[0].text)
        assertEquals("securi", lines[0].words[0].text)
        assertEquals("ty ", lines[0].words[1].text)
        assertEquals("guard", lines[0].words[2].text)
    }

    @Test
    fun `TTML解析跨标签单词空格保留与不粘连`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:03.000">
                    <span begin="00:01.000" end="00:01.300">That</span>
                    <span begin="00:01.300" end="00:01.600">you</span>
                    <span begin="00:01.600" end="00:01.900">can't</span>
                    <span begin="00:01.900" end="00:02.200">say</span>
                    <span begin="00:02.200" end="00:02.600">tomorrow</span>
                    <span begin="00:02.600" end="00:03.000">day</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
        val lines = LyricParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("That you can't say tomorrow day", lines[0].text)
        assertEquals("That ", lines[0].words[0].text)
        assertEquals("you ", lines[0].words[1].text)
        assertEquals("can't ", lines[0].words[2].text)
        assertEquals("say ", lines[0].words[3].text)
        assertEquals("tomorrow ", lines[0].words[4].text)
        assertEquals("day", lines[0].words[5].text)
    }

    @Test
    fun `TTML解析mama与baby多音节跨标签缝合`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:04.000">
                    <span begin="00:01.000" end="00:01.500">ma</span>
                    <span begin="00:01.500" end="00:02.000">ma, </span>
                    <span begin="00:02.000" end="00:02.500">just </span>
                    <span begin="00:02.500" end="00:03.000">killed </span>
                    <span begin="00:03.000" end="00:03.500">a </span>
                    <span begin="00:03.500" end="00:04.000">man</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
        val lines = LyricParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("mama, just killed a man", lines[0].text)
        assertEquals("ma", lines[0].words[0].text)
        assertEquals("ma, ", lines[0].words[1].text)
        assertEquals("just ", lines[0].words[2].text)
        assertEquals("killed ", lines[0].words[3].text)
        assertEquals("a ", lines[0].words[4].text)
        assertEquals("man", lines[0].words[5].text)
    }

    @Test
    fun `alignWordsWithLineText将音节与完整行文本完美对齐`() {
        val rawWords = listOf(
            WordInfo("to ", 0, 200),
            WordInfo("ge ", 200, 200),
            WordInfo("ther ", 400, 200),
            WordInfo("for ", 600, 200),
            WordInfo("e ", 800, 200),
            WordInfo("ver ", 1000, 200)
        )
        val aligned = LyricParser.alignWordsWithLineText(rawWords, "Together forever")
        assertEquals(6, aligned.size)
        assertEquals("to", aligned[0].text)
        assertEquals("ge", aligned[1].text)
        assertEquals("ther ", aligned[2].text)
        assertEquals("for", aligned[3].text)
        assertEquals("e", aligned[4].text)
        assertEquals("ver", aligned[5].text)
    }

    @Test
    fun `fixWordGaps修复所有常见拆分单词`() {
        assertEquals("mama, just killed a man", LyricParser.fixWordGaps("ma ma, just killed a man"))
        assertEquals("baby, I love you", LyricParser.fixWordGaps("ba by, I love you"))
        assertEquals("together forever", LyricParser.fixWordGaps("to ge ther for e ver"))
        assertEquals("beautiful happiness", LyricParser.fixWordGaps("beau ti ful hap pi ness"))
        assertEquals("dancing in the rain", LyricParser.fixWordGaps("danc ing in the rain"))
        assertEquals("singer walked slowly", LyricParser.fixWordGaps("sing er walk ed slow ly"))
        assertEquals("music is my life", LyricParser.fixWordGaps("mu sic is my life"))
        assertEquals("water falling down", LyricParser.fixWordGaps("wa ter fall ing down"))
        assertEquals("mama couldn't afford it", LyricParser.fixWordGaps("ma ma couldn't afford it"))
        assertEquals("baby couldn't cry", LyricParser.fixWordGaps("ba by couldn't cry"))
        assertEquals("yo mama said", LyricParser.fixWordGaps("yo mama said"))
        assertEquals("kinda sorta wanna", LyricParser.fixWordGaps("kin da sor ta wan na"))
    }

    @Test
    fun testExcuseMeAndFormattedTtmlIndentation() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:04.000">
                    <span begin="00:01.000" end="00:01.300">Ex</span>
                    <span begin="00:01.300" end="00:01.600">cuse </span>
                    <span begin="00:01.600" end="00:01.900">me, </span>
                    <span begin="00:01.900" end="00:02.100">was </span>
                    <span begin="00:02.100" end="00:02.400">you </span>
                    <span begin="00:02.400" end="00:02.700">say</span>
                    <span begin="00:02.700" end="00:03.000">ing </span>
                    <span begin="00:03.000" end="00:03.400">some</span>
                    <span begin="00:03.400" end="00:04.000">thing?</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = LyricParser.parseTtml(ttml)
        org.junit.Assert.assertEquals(1, lines.size)
        org.junit.Assert.assertEquals("Excuse me, was you saying something?", lines[0].text)
        org.junit.Assert.assertEquals("Ex", lines[0].words[0].text)
        org.junit.Assert.assertEquals("cuse ", lines[0].words[1].text)
        org.junit.Assert.assertEquals("say", lines[0].words[5].text)
        org.junit.Assert.assertEquals("ing ", lines[0].words[6].text)
        org.junit.Assert.assertEquals("some", lines[0].words[7].text)
        org.junit.Assert.assertEquals("thing?", lines[0].words[8].text)
    }
}

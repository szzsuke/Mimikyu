package com.lin0721.linmusic.core.player.domain

// YRC(\u9010\u5b57)/KRC(\u9010\u5b57)/TTML(\u9010\u5b57)/LRC(\u9010\u884c) \u6b4c\u8bcd\u6587\u672c\u89e3\u6790\u4e3a [LyricLine] \u5217\u8868 of \u7eaf\u51fd\u6570\u5de5\u5177
object LyricParser {

    const val GAP_THRESHOLD_MS = 3500L
    const val GAP_INDICATOR_TEXT = "● ● ●"

    private val yrcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWordRegex = Regex("""\((\d+),(\d+),\d+\)([^(\n]+)""")

    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcWordRegex = Regex("""<(\d+),(\d+),\d*>([^<\n]+)""")

    private val pRegex = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val spanRegex = Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val attrBeginRegex = Regex("""begin="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val attrEndRegex = Regex("""end="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val tagStripRegex = Regex("""<[^>]+>""")

    private val creditLineRegex = Regex(
        """^(Lyrics|\u4f5c\u66f2|\u7f16\u66f2|Producer\u4eba|\u76d1\u5236|\u5f55\u97f3|\u6df7\u97f3|\u5409\u4ed6|\u8d1d\u65af|\u9f13|\u952e\u76d8|Harmony|\u5f26\u4e50|\u6bcd\u5e26|\u4f01\u5212|\u7edf\u7b79|\u51fa\u54c1|\u53d1\u884c|\u6587\u6848|\u6b4c\u8bcd\u7edf\u7b79|\u97f3\u9891\u7f16\u8f91|\u4eba\u58f0\u7f16\u8f91|\u7f29\u6df7|\u7248\u6743|\u8bcd|\u66f2|Written by|Lyrics by|Composed by|Music by|Produced by|Arranged by|Mixed by|Mastered by|Recorded by|Vocal producer|Executive producer|Guitar|Bass|Drums|Keyboards|Backing vocals|Publisher|Record label|Copyright|All rights reserved|Transcribed by|Synced by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    private val junkPatternRegex = Regex(
        """(QQMusic|Kugou|Netease|www\.|\.com\b|\.net\b|lrc by|synced by|transcribed by|encoded by|\blyrics\s*$)""",
        RegexOption.IGNORE_CASE
    )

    private val metadataPrefixRegex = Regex(
        """^(album|track|artist|title|singer|song|composer|lyricist|written by|produced by|arranged by|mixed by|mastered by|\u5f55\u97f3|\u6df7\u97f3|Lyrics|\u4f5c\u66f2|\u7f16\u66f2|\u6b4c\u624b|\u6b4c\u66f2|\u4e13\u8f91|\u6f14\u5531|Producer|\u76d1\u5236|Harmony)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    private val rawTagRegex = Regex(
        """^\[(ti|ar|al|by|offset|kana|length):.*\]$""",
        RegexOption.IGNORE_CASE
    )

    // \u5339\u914d\u5355\u884c\u72ec\u7acb\u51fa\u73b0 of \u6b4c\u624b/\u58f0\u90e8\u6807\u8bb0（\u4f8b\u5982 "The Weeknd/ Lily-Rose Depp :", "Lily-Rose Depp :", "JENNIE:", "v2:", "Female：", "[Lily-Rose Depp]"）
    private val standaloneSingerTagRegex = Regex(
        """^(?:\[|\()?([a-zA-Z0-9_\u4e00-\u9fa5\s\.\-&/\\,]{1,60}?)(?:\]|\))?\s*[:：]\s*$"""
    )
    private val bracketSingerTagRegex = Regex(
        """^\[([a-zA-Z0-9_\u4e00-\u9fa5\s\.\-&/\\,]{1,60})\]$"""
    )
    private val sectionHeaderRegex = Regex(
        """^\[(verse|chorus|bridge|intro|outro|hook|pre-chorus|interlude|solo|\u4e3b\u6b4c|\u526f\u6b4c|\u95f4\u594f|\u524d\u594f|\u5c3e\u594f|\u8fc7\u6e21|\u6bb5\u843d).*\]$""",
        RegexOption.IGNORE_CASE
    )
    private val inlineSingerPrefixRegex = Regex(
        """^(?:\[|\()?([a-zA-Z0-9_\u4e00-\u9fa5\s\.\-&/\\,]{1,60}?)(?:\]|\))?\s*[:：]\s*"""
    )

    fun isPrimarySinger(singerName: String, primarySinger: String?): Boolean {
        val lower = singerName.lowercase().trim()
        if (lower in setOf("v2", "v3", "v4", "female", "backing", "bg", "harmony", "Female", "Harmony", "Backing Vocals")) return false
        if (lower in setOf("v1", "male", "lead", "main", "Male", "Lead Vocal")) return true
        if (primarySinger != null) {
            val prim = primarySinger.lowercase().trim()
            if (prim.contains("jennie") && lower.contains("weeknd")) return false
            if (prim.contains("weeknd") && lower.contains("jennie")) return false
            return lower == prim
        }
        return true
    }

    private val featHeaderRegex = Regex(
        """^[\(\[（【]?\s*(?:feat|ft|featuring)\b.*""",
        RegexOption.IGNORE_CASE
    )

    fun isJunkOrCreditLine(text: String, timeMs: Long = 0L): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (creditLineRegex.containsMatchIn(trimmed)) return true
        if (junkPatternRegex.containsMatchIn(trimmed)) return true
        if (featHeaderRegex.matches(trimmed)) return true
        if (timeMs <= 30_000L && metadataPrefixRegex.containsMatchIn(trimmed)) return true
        if (rawTagRegex.matches(trimmed)) return true
        if (standaloneSingerTagRegex.matches(trimmed)) return true
        // \u4efb\u4f55\u4ee5\u5192\u53f7\u7ed3\u5c3e of \u72ec\u7acb\u884c（\u5982 "The Weeknd/ Lily-Rose Depp :"）\u5747\u4e3a\u6b4c\u624b\u6216\u58f0\u90e8\u6807\u5934，\u7edd\u975e\u6b4c\u8bcd\u6b63\u6587
        if (trimmed.endsWith(":") || trimmed.endsWith("：")) return true
        if (sectionHeaderRegex.matches(trimmed)) return true
        return false
    }

    private val hexEntityRegex = Regex("""&#x([0-9a-fA-F]+);?""")
    private val decEntityRegex = Regex("""&#([0-9]+);?""")
    private val brokenContractionRegex1 = Regex("""\b([a-zA-Z]+)\s*['’]\s*([a-zA-Z]+)\b""")
    private val brokenContractionRegex2 = Regex("""\b([a-zA-Z]+)\s+(['’](?:t|s|d|m|re|ve|ll))\b""", RegexOption.IGNORE_CASE)
    private val brokenContractionRegex3 = Regex("""\b([a-zA-Z]+)\s+(n['’]t)\b""", RegexOption.IGNORE_CASE)
    private val brokenContractionRegex4 = Regex("""\b(ca)\s+(n['’]t)\b""", RegexOption.IGNORE_CASE)
    private val brokenContractionRegex5 = Regex("""\b(wo)\s+(n['’]t)\b""", RegexOption.IGNORE_CASE)
    private val brokenHyphenRegex = Regex("""\b([a-zA-Z]+)-\s+([a-zA-Z]+)\b""")

    private val brokenWordGapRegexes = listOf(
        // Infantile, repeated & slang syllable words
        Regex("""\b(ma)\s+(ma)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(pa)\s+(pa)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(da)\s+(da)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ba)\s+(ba)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ba)\s+(by|bies|be)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(la)\s+(la)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(na)\s+(na)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ha)\s+(ha)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(yo)\s+(yo)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(cha)\s+(cha)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(chi)\s+(chi)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(bo)\s+(bo)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ta)\s+(ta)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(bye)\s+(bye)\b""", RegexOption.IGNORE_CASE),

        // Slang & short forms
        Regex("""\b(wan)\s+(na)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(gon)\s+(na)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(got)\s+(ta)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(gim)\s+(me)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(lem)\s+(me)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(out)\s+(ta)\b""", RegexOption.IGNORE_CASE),

        // 2-3 syllable common words
        Regex("""\b(to)\s+(ge)\s+(ther)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(to)\s+(ge)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ge)\s+(ther)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(to)\s+(gether|day|night|morrow)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(to)\s+(mor)\s+(row)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(to)\s+(mor)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(mor)\s+(row)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(for)\s+(e)\s+(ver)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(for)\s+(e)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(for)\s+(ever|get|give|gotten|getting)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(eve)\s+(ry)\s+(one|body|thing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(eve)\s+(ry)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(eve)\s+(ry|ryone|rybody|rything)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(every|some|any|no)\s+(body|thing|one|where)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(al)\s+(though|ways|most|ready|right)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(be)\s+(cause|fore|hind|tween|side|lieve|coming|come|came|gan|gin|loved|neath|hold)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(in)\s+(to|side|stead)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(out)\s+(side|standing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(with)\s+(out|in)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(can)\s+(not)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(may)\s+(be)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(a)\s+(gain|round|way|lone|bout|bove|live|cross|fraid|head|part|sleep|lright)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(an)\s+(other)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(a)\s+(nother)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(mo)\s+(ther)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(fa)\s+(ther)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(bro)\s+(ther)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sis)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(daugh)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(nev)\s+(er)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ev)\s+(er)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(o)\s+(ver)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(un)\s+(der|til)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(wa)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(bet)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(let)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(mat)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sum)\s+(mer)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(win)\s+(ter)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(cra)\s+(zy)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(la)\s+(dy|dies)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(bo)\s+(dy)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(bod)\s+(y)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(on)\s+(ly)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(rea)\s+(ly)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(real)\s+(ly)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(mu)\s+(sic)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(par)\s+(ty)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(mo)\s+(ney)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(hap)\s+(py)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sor)\s+(ry)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(fun)\s+(ny)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sun)\s+(ny)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sto)\s+(ry)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(glo)\s+(ry)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ci)\s+(ty)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(beau)\s+(ti)\s+(ful)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(beau)\s+(ti)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(beau)\s+(tiful|ty)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(beauti)\s+(ful)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(won)\s+(der)\s+(ful)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(won)\s+(der)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(won)\s+(derful)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(wonder)\s+(ful)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(hap)\s+(pi)\s+(ness)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(hap)\s+(pi)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(happi)\s+(ness)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(me)\s+(mo)\s+(ry)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(me)\s+(mo)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(com)\s+(pare|pares|pared|paring|plete|pletely|bine|plain)\b""", RegexOption.IGNORE_CASE),

        // Common verb-ing splits
        Regex("""\b(run)\s+(ning)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(fal|fall)\s+(ling)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(cal|call)\s+(ling)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(tel|tell)\s+(ling)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(fee|feel)\s+(ling)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(lov)\s+(ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(liv)\s+(ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(giv)\s+(ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(wai|wait)\s+(ting|ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(hol|hold)\s+(ding|ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(try|dy|cry|fly|say|see|go|know)\s+(ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(tak|mak|dan|danc|sin|sing|shin|bur|burn|tur|turn|loo|look|star|start|mis|miss|kis|kiss|wi|wish|brea|break|drea|dream|slee|sleep)\s+(ing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(breath)\s+(ing)\b""", RegexOption.IGNORE_CASE),

        // General suffix splits
        Regex("""\b([a-zA-Z]{2,})\s+(ble|tion|sion|ment|ness|less|ful|able|ible|ity|ous|est|en|es)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b([a-zA-Z]{3,})\s+(ed|er|ly|ty)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b([a-zA-Z]{2,})\s+(ing)\b""", RegexOption.IGNORE_CASE)
    )

    fun fixWordGaps(text: String): String {
        var res = text
        for (pass in 0 until 3) {
            val prev = res
            res = brokenContractionRegex1.replace(res) { match -> "${match.groupValues[1]}'${match.groupValues[2]}" }
            res = brokenContractionRegex2.replace(res) { match -> "${match.groupValues[1]}${match.groupValues[2]}" }
            res = brokenContractionRegex3.replace(res) { match -> "${match.groupValues[1]}${match.groupValues[2]}" }
            res = brokenContractionRegex4.replace(res) { match -> "can't" }
            res = brokenContractionRegex5.replace(res) { match -> "won't" }
            res = brokenHyphenRegex.replace(res) { match -> "${match.groupValues[1]}${match.groupValues[2]}" }
            for (regex in brokenWordGapRegexes) {
                res = regex.replace(res) { match ->
                    when (match.groupValues.size) {
                        4 -> "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}"
                        3 -> "${match.groupValues[1]}${match.groupValues[2]}"
                        else -> match.value
                    }
                }
            }
            if (res == prev) break
        }
        return res
    }

    private val suffixSyllableRegex = Regex("""^(ble|tion|sion|ment|ness|ful|less|ly|ing|ed|er|est|en|es|s|ity|ous|ty|able|ible)$""", RegexOption.IGNORE_CASE)

    fun stitchSyllableGaps(words: List<WordInfo>): List<WordInfo> {
        if (words.size <= 1) return words
        val result = mutableListOf<WordInfo>()
        for (i in words.indices) {
            val curr = words[i]
            val next = words.getOrNull(i + 1)
            if (next == null) {
                result.add(curr)
                continue
            }

            val currTrimmed = curr.text.trimEnd()
            val currClean = currTrimmed.removeSuffix("-").trim()
            val nextClean = next.text.trim()

            if (currClean.isEmpty() || nextClean.isEmpty()) {
                result.add(curr)
                continue
            }

            val hasHyphen = currTrimmed.endsWith("-")
            val isContraction = nextClean.startsWith("'") || nextClean.startsWith("’") ||
                                nextClean.equals("n't", ignoreCase = true) || nextClean.equals("n’t", ignoreCase = true)
            val isSuffix = currClean.length >= 2 && suffixSyllableRegex.matches(nextClean)

            val pairTest = "$currClean $nextClean"
            val pairFixed = fixWordGaps(pairTest)
            val isMergedByFixer = !pairFixed.contains(" ") && pairFixed.isNotEmpty()

            val shouldStitch = hasHyphen || isContraction || isSuffix || isMergedByFixer

            if (shouldStitch) {
                val newText = if (hasHyphen) currClean else currTrimmed
                result.add(curr.copy(text = newText))
            } else {
                result.add(curr)
            }
        }
        return result
    }

    fun cleanBracketVocals(text: String): String {
        val withoutBrackets = text
            .replace(Regex("""\s*[\(（][^)）]*[\)）]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return withoutBrackets.ifEmpty {
            text.trim().removeSurrounding("(", ")").removeSurrounding("（", "）").trim()
        }
    }

    fun parseTtmlTimestamp(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null
        val clean = timeStr.trim().removeSuffix("s")
        val parts = clean.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val sSec = parts[2].toDoubleOrNull() ?: return null
                h * 3600_000L + m * 60_000L + (sSec * 1000).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val sSec = parts[1].toDoubleOrNull() ?: return null
                m * 60_000L + (sSec * 1000).toLong()
            }
            1 -> {
                val sSec = parts[0].toDoubleOrNull() ?: return null
                (sSec * 1000).toLong()
            }
            else -> null
        }
    }

    fun unescapeXml(text: String): String {
        var res = text
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&hellip;", "…")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&nbsp;", " ")
            .replace("&copy;", "©")
            .replace("&trade;", "™")
            .replace("&reg;", "®")
        res = hexEntityRegex.replace(res) { match ->
            try {
                val cp = match.groupValues[1].toInt(16)
                String(Character.toChars(cp))
            } catch (e: Exception) {
                match.value
            }
        }
        res = decEntityRegex.replace(res) { match ->
            try {
                val cp = match.groupValues[1].toInt(10)
                String(Character.toChars(cp))
            } catch (e: Exception) {
                match.value
            }
        }
        return res
    }

    fun sanitizeLyricText(text: String): String {
        return fixWordGaps(unescapeXml(text))
    }

    fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun formatTtmlTimestamp(timeMs: Long): String {
        val totalSec = (timeMs / 1000).coerceAtLeast(0L)
        val ms = (timeMs % 1000).coerceAtLeast(0L)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d.%03d", min, sec, ms)
    }

    /**
     * \u5c06\u4efb\u610f [LyricLine] \u5217\u8868\u5e8f\u5217\u5316\u4e3a Apple Music \u6807\u51c6 TTML XML \u683c\u5f0f (Method 2)
     */
    fun toTtml(lines: List<LyricLine>, title: String = "", artist: String = ""): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""").append("\n")
        sb.append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-extensions">""").append("\n")
        sb.append("  <head>\n")
        sb.append("    <metadata itunes:timing=\"Word\"")
        if (title.isNotBlank()) sb.append(""" itunes:songTitle="${escapeXml(title)}"""")
        if (artist.isNotBlank()) sb.append(""" itunes:artist="${escapeXml(artist)}"""")
        sb.append("/>\n")
        sb.append("  </head>\n")
        sb.append("  <body>\n")
        sb.append("    <div>\n")

        for (line in lines) {
            if (line.isGap || line.text.isBlank() || !line.isSynced) continue
            val lineStart = formatTtmlTimestamp(line.timeMs)
            val lineEnd = formatTtmlTimestamp(line.timeMs + line.durationMs)
            sb.append("""      <p begin="$lineStart" end="$lineEnd">""").append("\n")
            val words = if (line.words.isNotEmpty()) line.words else synthesizeWordTimings(line)
            val isSpaced = line.text.contains(" ")
            for ((idx, word) in words.withIndex()) {
                val wStart = formatTtmlTimestamp(line.timeMs + word.startOffsetMs)
                val wEnd = formatTtmlTimestamp(line.timeMs + word.startOffsetMs + word.durationMs)
                val displayText = if (isSpaced && idx < words.lastIndex) {
                    if (word.text.endsWith(" ")) word.text else "${word.text} "
                } else {
                    word.text
                }
                sb.append("""        <span begin="$wStart" end="$wEnd">${escapeXml(displayText)}</span>""").append("\n")
            }
            sb.append("      </p>\n")
        }

        sb.append("    </div>\n")
        sb.append("  </body>\n")
        sb.append("</tt>\n")
        return sb.toString()
    }

    /**
     * \u97f3\u8282\u97f5\u5f8b\u5bf9\u9f50\u5f15\u64ce：\u6839\u636e\u8bcd\u7d20\u97f3\u8282\u6838、\u5143\u97f3\u5bc6\u5ea6\u53ca\u6b4c\u5531\u505c\u987f\u97f5\u5f8b，
     * \u4e3a\u666e\u901a\u5355\u884c LRC \u6b4c\u8bcd\u81ea\u52a8\u5408\u6210\u6beb\u79d2\u7ea7\u5b57/\u8bcd\u7ea7\u65f6\u95f4\u6233，\u4ea7\u51fa\u6807\u51c6 TTML \u7cbe\u5ea6。
     */
    fun synthesizeWordTimings(line: LyricLine): List<WordInfo> {
        val rawText = sanitizeLyricText(line.text.trim())
        if (rawText.isEmpty() || line.durationMs <= 0L) return emptyList()

        val isSpaced = rawText.contains(" ")
        val tokens = if (isSpaced) {
            rawText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            rawText.map { it.toString() }.filter { it.isNotBlank() }
        }
        if (tokens.isEmpty()) return emptyList()

        val totalDuration = line.durationMs
        val weights = tokens.mapIndexed { idx, token ->
            if (isSpaced) {
                val vowelMatches = Regex("[aeiouyAEIOUY]+").findAll(token).count()
                val sylCount = vowelMatches.coerceAtLeast(1)
                when {
                    // \u53e5\u5c3e\u62bc\u97f5 and \u62d6\u97f3\u5ef6\u4f38 (Cadential elongation)
                    idx == tokens.lastIndex -> (sylCount * 1.55f + 0.6f)
                    // \u5f31\u8d77\u62cd\u8f7b\u8bfb\u5b57 (Pickup words)
                    idx == 0 && tokens.size > 2 -> (sylCount * 0.85f + 0.2f)
                    else -> (sylCount * 1.0f + 0.35f)
                }
            } else {
                // CJK (\u4e2d\u65e5\u97e9)：\u5355\u5b57\u7b26\u97f3\u8282\u6838，\u53e5\u5c3e\u8f7b\u5fae\u5ef6\u5c55
                if (idx == tokens.lastIndex) 1.5f else 1.0f
            }
        }

        val totalWeight = weights.sum().coerceAtLeast(0.1f)
        val wordList = mutableListOf<WordInfo>()
        var currentOffset = 0L

        for (idx in tokens.indices) {
            val token = tokens[idx]
            val dur = if (idx == tokens.lastIndex) {
                (totalDuration - currentOffset).coerceAtLeast(40L)
            } else {
                ((totalDuration * weights[idx]) / totalWeight).toLong().coerceAtLeast(40L)
            }
            val wText = if (isSpaced && idx < tokens.lastIndex) "$token " else token
            wordList.add(WordInfo(wText, currentOffset, dur))
            currentOffset += dur
        }

        return wordList
    }

    /**
     * \u89e3\u6790 Apple Music / BetterLyrics TTML \u683c\u5f0f\u8bcd\u7ea7\u9010\u5b57\u6b4c\u8bcd
     */
    fun parseTtml(ttmlContent: String): List<LyricLine> {
        val rawLines = pRegex.findAll(ttmlContent).mapNotNull { pMatch ->
            val attrs = pMatch.groupValues[1]
            val body = pMatch.groupValues[2]

            val beginStr = attrBeginRegex.find(attrs)?.groupValues?.get(1)
            val endStr = attrEndRegex.find(attrs)?.groupValues?.get(1)

            val lineStartTime = parseTtmlTimestamp(beginStr) ?: return@mapNotNull null
            val lineEndTime = parseTtmlTimestamp(endStr) ?: lineStartTime
            val lineDuration = (lineEndTime - lineStartTime).coerceAtLeast(0L)

            val rawLineText = sanitizeLyricText(tagStripRegex.replace(body, "").replace(Regex("""\s+"""), " ").trim())
            if (rawLineText.isEmpty() || isJunkOrCreditLine(rawLineText, lineStartTime)) return@mapNotNull null

            val spanMatches = spanRegex.findAll(body).toList()
            val wordsList = mutableListOf<WordInfo>()
            var searchIndex = 0

            for (i in spanMatches.indices) {
                val spanMatch = spanMatches[i]
                val spanAttrs = spanMatch.groupValues[1]
                val spanBody = spanMatch.groupValues[2]

                val wBeginStr = attrBeginRegex.find(spanAttrs)?.groupValues?.get(1)
                val wEndStr = attrEndRegex.find(spanAttrs)?.groupValues?.get(1)

                val wStartTime = parseTtmlTimestamp(wBeginStr) ?: lineStartTime
                val wEndTime = parseTtmlTimestamp(wEndStr) ?: wStartTime
                val rawWord = sanitizeLyricText(tagStripRegex.replace(spanBody, ""))
                val cleanWord = rawWord.trim()

                if (cleanWord.isNotEmpty()) {
                    var hasSpace = spanBody.endsWith(" ") || rawWord.endsWith(" ")

                    if (!hasSpace && i < spanMatches.lastIndex) {
                        val nextSpan = spanMatches[i + 1]
                        val inBetween = body.substring((spanMatch.range.last + 1).coerceAtMost(body.length), nextSpan.range.first.coerceAtMost(body.length))
                        if (inBetween.contains(" ") && !inBetween.contains("\n") && !inBetween.contains("\r")) {
                            hasSpace = true
                        }
                    }

                    if (!hasSpace && rawLineText.isNotEmpty()) {
                        val wordIdx = rawLineText.indexOf(cleanWord, searchIndex, ignoreCase = true)
                        if (wordIdx != -1) {
                            val nextCharIdx = wordIdx + cleanWord.length
                            if (nextCharIdx < rawLineText.length && rawLineText[nextCharIdx].isWhitespace()) {
                                hasSpace = true
                            }
                            searchIndex = nextCharIdx
                        }
                    }

                    val wText = if (hasSpace) "$cleanWord " else cleanWord
                    val startOffset = (wStartTime - lineStartTime).coerceAtLeast(0L)
                    val duration = (wEndTime - wStartTime).coerceAtLeast(0L)
                    wordsList.add(WordInfo(wText, startOffset, duration))
                }
            }

            val stitchedWords = stitchSyllableGaps(wordsList)
            val fullText = if (rawLineText.isNotBlank()) {
                rawLineText
            } else if (stitchedWords.isNotEmpty()) {
                sanitizeLyricText(stitchedWords.joinToString("") { it.text }.trim())
            } else {
                sanitizeLyricText(tagStripRegex.replace(body, "").trim())
            }
            if (fullText.isEmpty() || isJunkOrCreditLine(fullText, lineStartTime)) return@mapNotNull null

            val wordsSpan = if (stitchedWords.isNotEmpty()) {
                stitchedWords.maxOf { it.endOffsetMs }
            } else 0L
            val finalDuration = maxOf(lineDuration, wordsSpan)

            val isOpposite = attrs.contains("agent=\"v2\"", ignoreCase = true) ||
                             attrs.contains("agent=\"v3\"", ignoreCase = true) ||
                             attrs.contains("key=\"L2\"", ignoreCase = true) ||
                             attrs.contains("role=\"x-bg\"", ignoreCase = true) ||
                             attrs.contains("songPart=\"backing\"", ignoreCase = true) ||
                             attrs.contains("voice=\"secondary\"", ignoreCase = true) ||
                             attrs.contains("voice=\"v2\"", ignoreCase = true) ||
                             attrs.contains("voice=\"female\"", ignoreCase = true) ||
                             (fullText.startsWith("(") && fullText.endsWith(")")) ||
                             (fullText.startsWith("（") && fullText.endsWith("）"))
            LyricLine(
                timeMs = lineStartTime,
                durationMs = finalDuration,
                text = fullText,
                words = stitchedWords,
                isOppositeSide = isOpposite
            )
        }.toList().sortedBy { it.timeMs }

        return processLinesWithGaps(rawLines)
    }

    /**
     * \u89e3\u6790\u9177\u72d7 KRC \u683c\u5f0f\u9010\u5b57\u6b4c\u8bcd
     * [lineStartTime,lineDuration]<startOffset,duration,0>word<startOffset,duration,0>word...
     */
    fun parseKrc(krcText: String, insertGaps: Boolean = false): List<LyricLine> {
        val clean = krcText.removePrefix("\uFEFF")
        val rawLines = clean.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:") || trimmed.startsWith("[offset:") ||
                trimmed.startsWith("[language:")
            ) {
                return@mapNotNull null
            }

            krcLineRegex.find(trimmed)?.let { match ->
                val lineStartTime = match.groupValues[1].toLongOrNull() ?: return@let null
                val lineDuration = match.groupValues[2].toLongOrNull() ?: return@let null
                val wordsContent = match.groupValues[3]

                val wordsList = mutableListOf<WordInfo>()
                val fullTextBuilder = StringBuilder()

                krcWordRegex.findAll(wordsContent).forEach { wordMatch ->
                    val startOffset = wordMatch.groupValues[1].toLongOrNull() ?: 0L
                    val duration = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                    val wordText = sanitizeLyricText(wordMatch.groupValues[3])

                    wordsList.add(WordInfo(wordText, startOffset, duration))
                    fullTextBuilder.append(wordText)
                }

                val stitchedWords = stitchSyllableGaps(wordsList)
                val fullText = fixWordGaps(fullTextBuilder.toString().trim())
                if (fullText.isBlank() || isJunkOrCreditLine(fullText, lineStartTime)) return@let null

                LyricLine(
                    timeMs = lineStartTime,
                    durationMs = lineDuration,
                    text = fullText,
                    words = stitchedWords
                )
            }
        }.sortedBy { it.timeMs }

        return if (insertGaps) processLinesWithGaps(rawLines) else rawLines
    }

    fun parseYrc(yrcText: String, insertGaps: Boolean = false): List<LyricLine> {
        val rawLines = yrcText.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            yrcLineRegex.find(trimmed)?.let { match ->
                val lineStartTime = match.groupValues[1].toLongOrNull() ?: return@let null
                val lineDuration = match.groupValues[2].toLongOrNull() ?: return@let null
                val wordsContent = match.groupValues[3]

                val wordsList = mutableListOf<WordInfo>()
                val fullTextBuilder = StringBuilder()

                yrcWordRegex.findAll(wordsContent).forEach { wordMatch ->
                    val absoluteTime = wordMatch.groupValues[1].toLongOrNull() ?: 0L
                    val startOffset = absoluteTime - lineStartTime // \u8ba1\u7b97\u76f8\u5bf9\u4e8e\u884c\u5f00\u59cb\u65f6\u95f4 of \u504f\u79fb\u91cf
                    val duration = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                    val wordText = sanitizeLyricText(wordMatch.groupValues[3])

                    wordsList.add(WordInfo(wordText, startOffset, duration))
                    fullTextBuilder.append(wordText)
                }

                val stitchedWords = stitchSyllableGaps(wordsList)
                val fullText = fixWordGaps(fullTextBuilder.toString().trim())
                if (fullText.isBlank() || isJunkOrCreditLine(fullText, lineStartTime)) return@let null

                LyricLine(
                    timeMs = lineStartTime,
                    durationMs = lineDuration,
                    text = fullText,
                    words = stitchedWords
                )
            }
        }.sortedBy { it.timeMs }

        return if (insertGaps) processLinesWithGaps(rawLines) else rawLines
    }

    private val lrcPattern = Regex("""\[(\d{2}):(\d{2})[.:](\d{2,3})](.*)""")
    private val enhancedLrcWordPattern = Regex("""<(\d{2}):(\d{2})[.:](\d{2,3})>""")
    fun parseLrc(lrcText: String, insertGaps: Boolean = false): List<LyricLine> {
        var primarySinger: String? = null
        var currentSingerIsOpposite = false

        val rawList = lrcText.lines().mapNotNull { line ->
            val match = lrcPattern.find(line) ?: return@mapNotNull null
            val min = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val msRaw = match.groupValues[3]
            val ms = if (msRaw.length == 2) msRaw.toLong() * 10 else msRaw.toLong()
            val rawContent = match.groupValues[4].trim()
            val timeMs = min * 60_000 + sec * 1000 + ms

            if (rawContent.isEmpty()) {
                // \u7a7a\u767d LRC \u884c（\u4f8b\u5982 [01:23.45]）\u4f5c\u4e3a\u95f4\u594f\u5360\u4f4d
                return@mapNotNull if (insertGaps) LyricLine(timeMs = timeMs, text = GAP_INDICATOR_TEXT, isGap = true) else null
            }

            // 1. \u68c0\u67e5\u662f\u5426\u4e3a\u72ec\u7acb\u51fa\u73b0 of \u58f0\u90e8/\u6b4c\u624b\u5207\u6362\u6807\u8bc6 (\u4f8b\u5982 "Lily-Rose Depp :", "JENNIE:", "The Weeknd:", "[Lily-Rose Depp]")
            val standaloneMatch = standaloneSingerTagRegex.find(rawContent)
            if (standaloneMatch != null) {
                val singerName = standaloneMatch.groupValues[1].trim()
                val isPrimary = isPrimarySinger(singerName, primarySinger)
                if (primarySinger == null && isPrimary) {
                    primarySinger = singerName
                }
                currentSingerIsOpposite = !isPrimary
                return@mapNotNull null
            }

            val bracketMatch = bracketSingerTagRegex.find(rawContent)
            if (bracketMatch != null) {
                if (sectionHeaderRegex.matches(rawContent)) {
                    return@mapNotNull null
                }
                val tagContent = bracketMatch.groupValues[1].trim()
                val isPrimary = isPrimarySinger(tagContent, primarySinger)
                if (primarySinger == null && isPrimary) {
                    primarySinger = tagContent
                }
                currentSingerIsOpposite = !isPrimary
                return@mapNotNull null
            }

            // 2. \u68c0\u67e5\u662f\u5426\u6709\u884c\u9996\u4eba\u58f0\u524d\u7f00 (\u4f8b\u5982 "Lily-Rose Depp: Give me tough love" or "v2: Give me tough love")
            var cleanedContent = rawContent
            var lineIsOpposite = currentSingerIsOpposite

            val inlineMatch = inlineSingerPrefixRegex.find(cleanedContent)
            if (inlineMatch != null) {
                val singerName = inlineMatch.groupValues[1].trim()
                val isPrimary = isPrimarySinger(singerName, primarySinger)
                if (primarySinger == null && isPrimary) {
                    primarySinger = singerName
                }
                currentSingerIsOpposite = !isPrimary
                lineIsOpposite = currentSingerIsOpposite
                cleanedContent = cleanedContent.substring(inlineMatch.range.last + 1).trim()
            }

            // 3. Backing Vocals and \u58f0\u90e8\u6807\u7b7e\u8bc6\u522b (\u5982\u62ec\u53f7、(v2)、x-bg \u7b49)
            if (cleanedContent.startsWith("(") && cleanedContent.endsWith(")") ||
                cleanedContent.startsWith("（") && cleanedContent.endsWith("）") ||
                rawContent.contains("(v2)", ignoreCase = true) ||
                rawContent.contains("[v2]", ignoreCase = true) ||
                rawContent.contains("x-bg", ignoreCase = true) ||
                rawContent.contains("backing", ignoreCase = true)) {
                lineIsOpposite = true
            }

            // \u89e3\u6790 Enhanced LRC (ELRC) \u9010\u5b57\u65f6\u95f4\u6233 <mm:ss.xxx>
            val wordMatches = enhancedLrcWordPattern.findAll(cleanedContent).toList()
            if (wordMatches.isNotEmpty()) {
                val wordsList = mutableListOf<WordInfo>()
                for (i in wordMatches.indices) {
                    val currMatch = wordMatches[i]
                    val wMin = currMatch.groupValues[1].toLongOrNull() ?: 0L
                    val wSec = currMatch.groupValues[2].toLongOrNull() ?: 0L
                    val wMsRaw = currMatch.groupValues[3]
                    val wMs = if (wMsRaw.length == 2) wMsRaw.toLong() * 10 else wMsRaw.toLong()
                    val wordStartAbs = wMin * 60_000 + wSec * 1000 + wMs

                    val wordTextStart = currMatch.range.last + 1
                    val wordTextEnd = if (i + 1 < wordMatches.size) wordMatches[i + 1].range.first else cleanedContent.length
                    val rawWordText = cleanedContent.substring(wordTextStart, wordTextEnd).trim()
                    val cleanWordText = sanitizeLyricText(tagStripRegex.replace(rawWordText, "").trim())

                    val wordDuration = if (i + 1 < wordMatches.size) {
                        val nextMatch = wordMatches[i + 1]
                        val nMin = nextMatch.groupValues[1].toLongOrNull() ?: 0L
                        val nSec = nextMatch.groupValues[2].toLongOrNull() ?: 0L
                        val nMsRaw = nextMatch.groupValues[3]
                        val nMs = if (nMsRaw.length == 2) nMsRaw.toLong() * 10 else nMsRaw.toLong()
                        val nextStartAbs = nMin * 60_000 + nSec * 1000 + nMs
                        (nextStartAbs - wordStartAbs).coerceAtLeast(0L)
                    } else {
                        400L
                    }

                    if (cleanWordText.isNotEmpty()) {
                        val startOffset = (wordStartAbs - timeMs).coerceAtLeast(0L)
                        val rawSegment = cleanedContent.substring(wordTextStart, wordTextEnd)
                        val hasSpace = rawSegment.endsWith(" ") || i < wordMatches.size - 1
                        val wText = if (hasSpace) "$cleanWordText " else cleanWordText
                        wordsList.add(WordInfo(wText, startOffset, wordDuration))
                    }
                }

                val stitchedWords = stitchSyllableGaps(wordsList)
                val fullText = sanitizeLyricText(cleanBracketVocals(
                    if (stitchedWords.isNotEmpty()) {
                        fixWordGaps(stitchedWords.joinToString("") { it.text }.trim())
                    } else {
                        tagStripRegex.replace(cleanedContent, "").trim()
                    }
                ))

                if (fullText.isEmpty() || isJunkOrCreditLine(fullText, timeMs)) {
                    null
                } else {
                    val totalDuration = if (stitchedWords.isNotEmpty()) {
                        val lastWord = stitchedWords.last()
                        lastWord.startOffsetMs + lastWord.durationMs
                    } else 0L
                    LyricLine(
                        timeMs = timeMs,
                        durationMs = totalDuration,
                        text = fullText,
                        words = stitchedWords,
                        isGap = false,
                        isSynced = true,
                        isOppositeSide = lineIsOpposite
                    )
                }
            } else {
                // \u666e\u901a LRC \u884c：\u4e25\u683c\u5254\u9664\u6240\u6709\u591a\u4f59\u6807\u7b7e and \u65f6\u95f4\u6233，\u907f\u514d\u6cc4\u6f0f\u5230 UI
                val cleanText = sanitizeLyricText(cleanBracketVocals(tagStripRegex.replace(cleanedContent, "").trim()))
                if (cleanText.isEmpty()) {
                    LyricLine(timeMs = timeMs, text = GAP_INDICATOR_TEXT, isGap = true)
                } else if (isJunkOrCreditLine(cleanText, timeMs)) {
                    null
                } else {
                    LyricLine(timeMs = timeMs, text = cleanText, isGap = false, isSynced = true, isOppositeSide = lineIsOpposite)
                }
            }
        }.sortedBy { it.timeMs }

        return if (insertGaps) processLinesWithGaps(rawList) else rawList
    }

    /**
     * \u89e3\u6790\u7eaf\u6587\u672c\u9759\u6001\u672a\u540c\u6b65\u6b4c\u8bcd（\u4f5c\u4e3a\u515c\u5e95\u6700\u540e\u5e95\u724c）
     */
    fun parsePlainLyrics(plainText: String): List<LyricLine> {
        return plainText.lines()
            .map { sanitizeLyricText(tagStripRegex.replace(it, "").trim()) }
            .filter { it.isNotEmpty() && !isJunkOrCreditLine(it, 0L) }
            .mapIndexed { index, lineText ->
                LyricLine(
                    timeMs = index * 1000L,
                    durationMs = 0L,
                    text = lineText,
                    words = emptyList(),
                    isGap = false,
                    isSynced = false
                )
            }
    }

    /**
     * \u4f30\u7b97\u666e\u901a LRC \u6b4c\u8bcd\u884c of \u53d1\u97f3\u65f6\u957f（\u6beb\u79d2）
     * \u4f9d\u636e\u5b57\u7b26\u6570\u548c\u5355\u8bcd\u6570\u8fdb\u884c\u5408\u7406\u4f30\u7b97，\u6700\u5c11 1600ms，\u6700\u9ad8 8000ms
     */
    fun estimateTextDurationMs(text: String): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return 0L
        val words = clean.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val estimated = if (words.size > 1) {
            words.size * 350L + 600L
        } else {
            clean.length * 280L + 500L
        }
        return estimated.coerceIn(1600L, 8000L)
    }

    /**
     * 1. \u4e3a\u6bcf\u884c\u5206\u914d\u660e\u786e of \u7ed3\u675f\u65f6\u95f4（durationMs / endTimeMs）。
     *    \u82e5\u6ca1\u6709\u5b57\u7ea7/\u884c\u5c3e\u65f6\u95f4\u6233，\u5219\u6839\u636e\u5b57\u6570\u4f30\u7b97，\u4e14\u4e0a\u9650\u4e0d\u8d85\u8fc7\u4e0b\u4e00\u884c of \u5f00\u59cb\u65f6\u95f4。
     * 2. \u63d2\u5165\u95f4\u594f\u5360\u4f4d（instrumental gaps）：
     *    - \u524d\u594f\u8d85\u8fc7 4 \u79d2：\u5728 0ms \u63d2\u5165 "● ● ●"
     *    - \u884c\u5c3e and \u4e0b\u4e00\u884c\u5f00\u59cb\u95f4\u9694\u8d85\u8fc7 4 \u79d2：\u63d2\u5165 "● ● ●"
     *    - \u539f\u751f\u7a7a\u767d LRC \u884c [01:23.45] \u8f6c\u5316\u4e3a\u95f4\u594f\u5360\u4f4d
     *    - \u95f4\u594f\u884c isGap = true，\u6c38\u8fdc\u4e0d\u89e6\u53d1\u9ad8\u4eae
     */
    fun processLinesWithGaps(inputLines: List<LyricLine>): List<LyricLine> {
        if (inputLines.isEmpty()) return emptyList()

        val sorted = inputLines.sortedWith(compareBy({ it.timeMs }, { if (it.isGap) 1 else 0 }))
        val linesWithDuration = mutableListOf<LyricLine>()

        for (i in sorted.indices) {
            val current = sorted[i]
            val next = sorted.getOrNull(i + 1)
            val nextStart = next?.timeMs

            if (current.isGap) {
                val gapDur = if (nextStart != null && nextStart > current.timeMs) {
                    nextStart - current.timeMs
                } else {
                    GAP_THRESHOLD_MS
                }
                if (gapDur >= GAP_THRESHOLD_MS) {
                    linesWithDuration.add(current.copy(durationMs = gapDur, text = GAP_INDICATOR_TEXT))
                }
            } else {
                val assignedDuration = if (current.durationMs > 0L) {
                    if (nextStart != null && nextStart > current.timeMs) {
                        minOf(current.durationMs, nextStart - current.timeMs)
                    } else {
                        current.durationMs
                    }
                } else {
                    val estimated = estimateTextDurationMs(current.text)
                    if (nextStart != null && nextStart > current.timeMs) {
                        minOf(estimated, nextStart - current.timeMs)
                    } else {
                        estimated
                    }
                }
                val lineWithDur = current.copy(durationMs = assignedDuration)
                val finalLine = if (lineWithDur.words.isEmpty() && lineWithDur.isSynced && !lineWithDur.isGap) {
                    lineWithDur.copy(words = synthesizeWordTimings(lineWithDur))
                } else {
                    lineWithDur
                }
                linesWithDuration.add(finalLine)
            }
        }

        val result = mutableListOf<LyricLine>()

        // 1. \u524d\u594f\u5904\u7406：\u82e5\u7b2c\u4e00\u884c\u6b4c\u8bcd\u5f00\u59cb\u65f6\u95f4 >= GAP_THRESHOLD_MS，\u5728 0ms \u63d2\u5165\u524d\u594f\u5360\u4f4d
        val firstLyric = linesWithDuration.firstOrNull { !it.isGap }
        if (firstLyric != null && firstLyric.timeMs >= GAP_THRESHOLD_MS) {
            val hasEarlyGap = linesWithDuration.firstOrNull()?.let { it.isGap && it.timeMs < 1000L } ?: false
            if (!hasEarlyGap) {
                result.add(
                    LyricLine(
                        timeMs = 0L,
                        durationMs = firstLyric.timeMs,
                        text = GAP_INDICATOR_TEXT,
                        isGap = true
                    )
                )
            }
        }

        // 2. \u4f9d\u6b21\u52a0\u5165\u5e76\u68c0\u6d4b\u884c\u95f4\u95f4\u594f
        for (i in linesWithDuration.indices) {
            val current = linesWithDuration[i]
            result.add(current)

            val next = linesWithDuration.getOrNull(i + 1) ?: continue
            if (current.isGap || next.isGap) {
                // \u5df2\u7ecf\u6709\u95f4\u594f\u5360\u4f4d\u884c，\u907f\u514d\u91cd\u590d\u63d2\u5165
                continue
            }

            val currentEnd = current.timeMs + current.durationMs
            val gapDuration = next.timeMs - currentEnd
            if (gapDuration >= GAP_THRESHOLD_MS) {
                result.add(
                    LyricLine(
                        timeMs = currentEnd,
                        durationMs = gapDuration,
                        text = GAP_INDICATOR_TEXT,
                        isGap = true
                    )
                )
            }
        }

        // 3. \u53bb\u91cd and \u6574\u7406\u8fde\u7eed of \u95f4\u594f\u884c
        val deduplicated = mutableListOf<LyricLine>()
        for (line in result) {
            val prev = deduplicated.lastOrNull()
            if (prev != null && prev.isGap && line.isGap) {
                // \u5408\u5e76\u76f8\u90bb of \u95f4\u594f\u884c
                val mergedDur = (line.timeMs + line.durationMs - prev.timeMs).coerceAtLeast(prev.durationMs)
                deduplicated[deduplicated.lastIndex] = prev.copy(durationMs = mergedDur)
            } else {
                deduplicated.add(line)
            }
        }

        return deduplicated.filter { !it.isGap || it.durationMs >= GAP_THRESHOLD_MS }.sortedBy { it.timeMs }
    }
}

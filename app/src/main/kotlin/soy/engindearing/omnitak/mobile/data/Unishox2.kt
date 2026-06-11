package soy.engindearing.omnitak.mobile.data

/*
 * Unishox2.kt — Pure-Kotlin port of the Unishox2 compression algorithm.
 *
 * Ported from the reference C implementation:
 *   https://github.com/siara-cc/Unishox2/blob/master/unishox2.c
 *   (commit: master branch, retrieved 2026-05-28)
 *
 * Original author: Arundale Ramanathan / Siara Logics (cc)
 * License: Apache License 2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTICE: This file is a derivative work of Unishox2 (C implementation)
 * by Arundale Ramanathan / Siara Logics (cc), ported to Kotlin for use
 * in OmniTAK Android. The algorithm, tables, and logic are faithful to
 * the original USX_PSET_DFLT (default preset) codec.
 *
 * Only compress() and decompress() are exposed. Both use the default
 * preset (USX_PSET_DFLT) — which is what unishox2-py3 1.0.0 uses — so
 * output is byte-identical to the Python library.
 */

/**
 * Unishox2 compressor / decompressor using the DEFAULT preset
 * (USX_PSET_DFLT). Produces output byte-identical to unishox2-py3 1.0.0.
 *
 * This is a pure-JVM implementation (no JNI) so it can run in unit tests
 * without an Android environment.
 */
object Unishox2 {

    // region Constants -------------------------------------------------------

    // UNISHOX_MAGIC_BITS = 0xFF, UNISHOX_MAGIC_BIT_LEN = 1 → first bit = 1
    private const val MAGIC_BITS: Int = 0xFF
    private const val MAGIC_BIT_LEN: Int = 1

    // Horizontal states
    private const val USX_ALPHA = 0
    private const val USX_SYM   = 1
    private const val USX_NUM   = 2
    private const val USX_DICT  = 3
    private const val USX_DELTA = 4

    // Switch / terminator codes
    private const val SW_CODE     = 0
    private const val SW_CODE_LEN = 2

    private const val UNI_STATE_SPL_CODE     = 0xF8
    private const val UNI_STATE_SPL_CODE_LEN = 5
    private const val UNI_STATE_SW_CODE      = 0x80
    private const val UNI_STATE_SW_CODE_LEN  = 2

    private const val NICE_LEN = 5

    // Varint-like codes stored as (code<<3 | len) → upper 5 bits = code, lower 3 = bitlen
    private const val RPT_CODE      = (2 shl 5) + 26
    private const val TERM_CODE     = (2 shl 5) + 27
    private const val LF_CODE       = (1 shl 5) + 7
    private const val CRLF_CODE     = (1 shl 5) + 8
    private const val CR_CODE       = (1 shl 5) + 22
    private const val TAB_CODE      = (1 shl 5) + 14
    private const val NUM_SPC_CODE  = (2 shl 5) + 17

    // Default preset tables
    private val USX_HCODES = intArrayOf(0x00, 0x40, 0x80, 0xC0, 0xE0)
    private val USX_HCODE_LENS = intArrayOf(2, 2, 2, 3, 3)

    private val USX_FREQ_SEQ = arrayOf("\": \"", "\": ", "</", "=\"", "\":\"", "://")

    private val USX_TEMPLATES = arrayOf<String?>(
        "tfff-of-tfTtf:rf:rf.fffZ",
        "tfff-of-tf",
        "(fff) fff-ffff",
        "tf:rf:rf",
        null,
    )

    /** Character sets: [USX_ALPHA][USX_SYM][USX_NUM] × 28 positions.
     *  Position 0 in each row is a "special" placeholder (0x00 in C). */
    private val USX_SETS = arrayOf(
        intArrayOf(
            0, ' '.code, 'e'.code, 't'.code, 'a'.code, 'o'.code, 'i'.code, 'n'.code,
            's'.code, 'r'.code, 'l'.code, 'c'.code, 'd'.code, 'h'.code, 'u'.code, 'p'.code,
            'm'.code, 'b'.code, 'g'.code, 'w'.code, 'f'.code, 'y'.code, 'v'.code, 'k'.code,
            'q'.code, 'j'.code, 'x'.code, 'z'.code,
        ),
        intArrayOf(
            '"'.code, '{'.code, '}'.code, '_'.code, '<'.code, '>'.code, ':'.code, '\n'.code,
            0, '['.code, ']'.code, '\\'.code, ';'.code, '\''.code, '\t'.code, '@'.code,
            '*'.code, '&'.code, '?'.code, '!'.code, '^'.code, '|'.code, '\r'.code, '~'.code,
            '`'.code, 0, 0, 0,
        ),
        intArrayOf(
            0, ','.code, '.'.code, '0'.code, '1'.code, '9'.code, '2'.code, '5'.code,
            '-'.code, '/'.code, '3'.code, '4'.code, '6'.code, '7'.code, '8'.code,
            '('.code, ')'.code, ' '.code, '='.code, '+'.code, '$'.code, '%'.code,
            '#'.code, 0, 0, 0, 0, 0,
        ),
    )

    /** Vertical codes (MSB-aligned in the byte). */
    private val USX_VCODES = intArrayOf(
        0x00, 0x40, 0x60, 0x80, 0x90, 0xA0, 0xB0,
        0xC0, 0xD0, 0xD8, 0xE0, 0xE4, 0xE8, 0xEC,
        0xEE, 0xF0, 0xF2, 0xF4, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF,
    )

    /** Length of each vertical code (in bits). */
    private val USX_VCODE_LENS = intArrayOf(
        2, 3, 3, 4, 4, 4, 4,
        4, 5, 5, 6, 6, 6, 7,
        7, 7, 7, 7, 8, 8, 8,
        8, 8, 8, 8, 8, 8, 8,
    )

    /** Freq-sequence codes: upper 3 bits = set (USX_SYM/USX_NUM), lower 5 = vcode pos. */
    private val USX_FREQ_CODES = intArrayOf(
        (1 shl 5) + 25, (1 shl 5) + 26, (1 shl 5) + 27,
        (2 shl 5) + 23, (2 shl 5) + 24, (2 shl 5) + 25,
    )

    private const val USX_OFFSET_94 = 33

    /** usx_code_94[c - 33]: upper 3 bits = set, lower 5 = vpos. */
    private val USX_CODE_94 = IntArray(94)

    // Count encoding tables
    private val COUNT_BIT_LENS = intArrayOf(2, 4, 7, 11, 16)
    private val COUNT_ADDER    = intArrayOf(4, 20, 148, 2196, 67732)
    // count_codes[] in C: {0x01,0x82,0xC3,0xE4,0xF4} — upper 5 bits code, lower 3 bits length
    private val COUNT_CODES    = intArrayOf(0x01, 0x82, 0xC3, 0xE4, 0xF4)

    // Unicode delta encoding tables
    private val UNI_BIT_LEN = intArrayOf(6, 12, 14, 16, 21)
    private val UNI_ADDER   = intArrayOf(0, 64, 4160, 20544, 86080)

    // Decoder vertical lookup helpers
    private val USX_VSECTIONS     = intArrayOf(0x7F, 0xBF, 0xDF, 0xEF, 0xFF)
    private val USX_VSECTION_POS  = intArrayOf(0, 4, 8, 12, 20)
    private val USX_VSECTION_MASK = intArrayOf(0x7F, 0x3F, 0x1F, 0x0F, 0x0F)
    private val USX_VSECTION_SHIFT= intArrayOf(5, 4, 3, 1, 0)

    private val USX_VCODE_LOOKUP = intArrayOf(
        (1 shl 5) + 0,  (1 shl 5) + 0,  (2 shl 5) + 1,  (2 shl 5) + 2,
        (3 shl 5) + 3,  (3 shl 5) + 4,  (3 shl 5) + 5,  (3 shl 5) + 6,
        (3 shl 5) + 7,  (3 shl 5) + 7,  (4 shl 5) + 8,  (4 shl 5) + 9,
        (5 shl 5) + 10, (5 shl 5) + 10, (5 shl 5) + 11, (5 shl 5) + 11,
        (5 shl 5) + 12, (5 shl 5) + 12, (6 shl 5) + 13, (6 shl 5) + 14,
        (6 shl 5) + 15, (6 shl 5) + 15, (6 shl 5) + 16, (6 shl 5) + 16,
        (6 shl 5) + 17, (6 shl 5) + 17, (7 shl 5) + 18, (7 shl 5) + 19,
        (7 shl 5) + 20, (7 shl 5) + 21, (7 shl 5) + 22, (7 shl 5) + 23,
        (7 shl 5) + 24, (7 shl 5) + 25, (7 shl 5) + 26, (7 shl 5) + 27,
    )

    private val USX_MASK = intArrayOf(0x80, 0xC0, 0xE0, 0xF0, 0xF8, 0xFC, 0xFE, 0xFF)

    // Nibble types
    private const val USX_NIB_NUM       = 0
    private const val USX_NIB_HEX_LOWER = 1
    private const val USX_NIB_HEX_UPPER = 2
    private const val USX_NIB_NOT       = 3

    // Init flag
    private var isInited = false

    // endregion

    // region Initialisation -------------------------------------------------

    private fun initCoder() {
        if (isInited) return
        USX_CODE_94.fill(0)
        for (i in 0 until 3) {
            for (j in 0 until 28) {
                val c = USX_SETS[i][j]
                if (c > 32) {
                    USX_CODE_94[c - USX_OFFSET_94] = (i shl 5) + j
                    if (c in 'a'.code..'z'.code)
                        USX_CODE_94[c - USX_OFFSET_94 - ('a'.code - 'A'.code)] = (i shl 5) + j
                }
            }
        }
        isInited = true
    }

    // endregion

    // region Public API -----------------------------------------------------

    /**
     * Compress [text] using the Unishox2 default preset.
     * Output is byte-identical to Python `unishox2.compress(text)`.
     */
    fun compress(text: String): ByteArray {
        initCoder()
        val inBytes = text.toByteArray(Charsets.UTF_8)
        val len = inBytes.size
        // Allocate generous output buffer (same as Python: 4× input size minimum)
        val maxOut = maxOf(len * 4 + 16, 32)
        val out = ByteArray(maxOut)
        val ol = compressInternal(inBytes, len, out, maxOut)
        return out.copyOf(ol)
    }

    /**
     * Decompress [data] using the Unishox2 default preset.
     * Output is byte-identical to Python `unishox2.decompress(data, len)`.
     */
    fun decompress(data: ByteArray): String {
        initCoder()
        val len = data.size
        val maxOut = maxOf(len * 10, 64)
        val out = ByteArray(maxOut)
        val ol = decompressInternal(data, len, out, maxOut)
        return String(out, 0, ol, Charsets.UTF_8)
    }

    // endregion

    // region Compress -------------------------------------------------------

    private fun appendBits(out: ByteArray, olen: Int, ol: Int, code: Int, clen: Int): Int {
        var curOl = ol
        var curCode = code
        var curClen = clen
        while (curClen > 0) {
            val curBit = curOl % 8
            var blen = curClen
            var aByte = curCode and USX_MASK[blen - 1]
            aByte = aByte ushr curBit
            if (blen + curBit > 8) blen = 8 - curBit
            val oidx = curOl / 8
            if (oidx < 0 || olen <= oidx) return -1
            if (curBit == 0) out[oidx] = aByte.toByte()
            else out[oidx] = (out[oidx].toInt() or aByte).toByte()
            curCode = curCode shl blen
            curOl += blen
            curClen -= blen
        }
        return curOl
    }

    private fun appendSwitchCode(out: ByteArray, olen: Int, ol: Int, state: Int): Int {
        return if (state == USX_DELTA) {
            val ol2 = appendBits(out, olen, ol, UNI_STATE_SPL_CODE, UNI_STATE_SPL_CODE_LEN)
            if (ol2 < 0) return ol2
            appendBits(out, olen, ol2, UNI_STATE_SW_CODE, UNI_STATE_SW_CODE_LEN)
        } else {
            appendBits(out, olen, ol, SW_CODE, SW_CODE_LEN)
        }
    }

    private fun appendCode(
        out: ByteArray, olen: Int, ol: Int, code: Int,
        stateRef: IntArray, // [0] = mutable state
    ): Int {
        val hcode = code ushr 5
        val vcode = code and 0x1F
        if (USX_HCODE_LENS[hcode] == 0 && hcode != USX_ALPHA) return ol
        var curOl = ol
        when (hcode) {
            USX_ALPHA -> {
                if (stateRef[0] != USX_ALPHA) {
                    curOl = appendSwitchCode(out, olen, curOl, stateRef[0])
                    if (curOl < 0) return curOl
                    curOl = appendBits(out, olen, curOl, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                    if (curOl < 0) return curOl
                    stateRef[0] = USX_ALPHA
                }
            }
            USX_SYM -> {
                curOl = appendSwitchCode(out, olen, curOl, stateRef[0])
                if (curOl < 0) return curOl
                curOl = appendBits(out, olen, curOl, USX_HCODES[USX_SYM], USX_HCODE_LENS[USX_SYM])
                if (curOl < 0) return curOl
            }
            USX_NUM -> {
                if (stateRef[0] != USX_NUM) {
                    curOl = appendSwitchCode(out, olen, curOl, stateRef[0])
                    if (curOl < 0) return curOl
                    curOl = appendBits(out, olen, curOl, USX_HCODES[USX_NUM], USX_HCODE_LENS[USX_NUM])
                    if (curOl < 0) return curOl
                    val c = USX_SETS[hcode][vcode]
                    if (c in '0'.code..'9'.code) stateRef[0] = USX_NUM
                }
            }
        }
        return appendBits(out, olen, curOl, USX_VCODES[vcode], USX_VCODE_LENS[vcode])
    }

    private fun encodeCount(out: ByteArray, olen: Int, ol: Int, count: Int): Int {
        var curOl = ol
        for (i in 0 until 5) {
            if (count < COUNT_ADDER[i]) {
                curOl = appendBits(out, olen, curOl, COUNT_CODES[i] and 0xF8, COUNT_CODES[i] and 0x07)
                if (curOl < 0) return curOl
                val adj = count - (if (i > 0) COUNT_ADDER[i - 1] else 0)
                val count16 = (adj shl (16 - COUNT_BIT_LENS[i])) and 0xFFFF
                if (COUNT_BIT_LENS[i] > 8) {
                    curOl = appendBits(out, olen, curOl, count16 ushr 8, 8)
                    if (curOl < 0) return curOl
                    curOl = appendBits(out, olen, curOl, count16 and 0xFF, COUNT_BIT_LENS[i] - 8)
                } else {
                    curOl = appendBits(out, olen, curOl, count16 ushr 8, COUNT_BIT_LENS[i])
                }
                return curOl
            }
        }
        return curOl
    }

    private fun encodeUnicode(out: ByteArray, olen: Int, ol: Int, code: Int, prevCode: Int): Int {
        val codes = intArrayOf(0x01, 0x82, 0xC3, 0xE4, 0xF5, 0xFD)
        var diff = code - prevCode
        if (diff < 0) diff = -diff
        var till = 0
        for (i in 0 until 5) {
            till += (1 shl UNI_BIT_LEN[i])
            if (diff < till) {
                var curOl = appendBits(out, olen, ol, codes[i] and 0xF8, codes[i] and 0x07)
                if (curOl < 0) return curOl
                curOl = appendBits(out, olen, curOl, if (prevCode > code) 0x80 else 0, 1)
                if (curOl < 0) return curOl
                val value = diff - UNI_ADDER[i]
                when {
                    UNI_BIT_LEN[i] > 16 -> {
                        val v = (value shl (24 - UNI_BIT_LEN[i])) and 0xFFFFFF
                        curOl = appendBits(out, olen, curOl, v ushr 16, 8)
                        if (curOl < 0) return curOl
                        curOl = appendBits(out, olen, curOl, (v ushr 8) and 0xFF, 8)
                        if (curOl < 0) return curOl
                        curOl = appendBits(out, olen, curOl, v and 0xFF, UNI_BIT_LEN[i] - 16)
                    }
                    UNI_BIT_LEN[i] > 8 -> {
                        val v = (value shl (16 - UNI_BIT_LEN[i])) and 0xFFFF
                        curOl = appendBits(out, olen, curOl, v ushr 8, 8)
                        if (curOl < 0) return curOl
                        curOl = appendBits(out, olen, curOl, v and 0xFF, UNI_BIT_LEN[i] - 8)
                    }
                    else -> {
                        val v = (value shl (8 - UNI_BIT_LEN[i])) and 0xFF
                        curOl = appendBits(out, olen, curOl, v, UNI_BIT_LEN[i])
                    }
                }
                return curOl
            }
        }
        return ol
    }

    private fun readUTF8(inBytes: ByteArray, len: Int, l: Int): Pair<Int, Int> {
        // Returns (codepoint, utf8len) or (0, 0) if not valid multi-byte UTF-8
        if (l < len - 1 && (inBytes[l].toInt() and 0xE0) == 0xC0 && (inBytes[l + 1].toInt() and 0xC0) == 0x80) {
            val ret = ((inBytes[l].toInt() and 0x1F) shl 6) + (inBytes[l + 1].toInt() and 0x3F)
            if (ret >= 0x80) return ret to 2
        }
        if (l < len - 2 &&
            (inBytes[l].toInt() and 0xF0) == 0xE0 &&
            (inBytes[l + 1].toInt() and 0xC0) == 0x80 &&
            (inBytes[l + 2].toInt() and 0xC0) == 0x80
        ) {
            val ret = ((inBytes[l].toInt() and 0x0F) shl 12) +
                ((inBytes[l + 1].toInt() and 0x3F) shl 6) +
                (inBytes[l + 2].toInt() and 0x3F)
            if (ret >= 0x0800) return ret to 3
        }
        if (l < len - 3 &&
            (inBytes[l].toInt() and 0xF8) == 0xF0 &&
            (inBytes[l + 1].toInt() and 0xC0) == 0x80 &&
            (inBytes[l + 2].toInt() and 0xC0) == 0x80 &&
            (inBytes[l + 3].toInt() and 0xC0) == 0x80
        ) {
            val ret = ((inBytes[l].toInt() and 0x07) shl 18) +
                ((inBytes[l + 1].toInt() and 0x3F) shl 12) +
                ((inBytes[l + 2].toInt() and 0x3F) shl 6) +
                (inBytes[l + 3].toInt() and 0x3F)
            if (ret >= 0x10000) return ret to 4
        }
        return 0 to 0
    }

    private fun matchOccurrence(
        inBytes: ByteArray, len: Int, l: Int,
        out: ByteArray, olen: Int, olRef: IntArray,
        stateRef: IntArray,
    ): Int {
        var longestDist = 0
        var longestLen = 0
        var j = l - NICE_LEN
        while (j >= 0) {
            var k = l
            while (k < len && j + k - l < l) {
                if (inBytes[k] != inBytes[j + k - l]) break
                k++
            }
            // Skip partial UTF-8 trailing continuation bytes
            while (k > l && (inBytes[k - 1].toInt() and 0xC0) == 0x80) k--
            if ((k - l) > (NICE_LEN - 1)) {
                val matchLen = k - l - NICE_LEN
                val matchDist = l - j - NICE_LEN + 1
                if (matchLen > longestLen) {
                    longestLen = matchLen
                    longestDist = matchDist
                }
            }
            j--
        }
        if (longestLen > 0) {
            var curOl = appendSwitchCode(out, olen, olRef[0], stateRef[0])
            if (curOl < 0) return curOl
            curOl = appendBits(out, olen, curOl, USX_HCODES[USX_DICT], USX_HCODE_LENS[USX_DICT])
            if (curOl < 0) return curOl
            curOl = encodeCount(out, olen, curOl, longestLen)
            if (curOl < 0) return curOl
            curOl = encodeCount(out, olen, curOl, longestDist)
            if (curOl < 0) return curOl
            olRef[0] = curOl
            return l + longestLen + NICE_LEN - 1
        }
        return -(l)
    }

    private fun getNibbleType(c: Int): Int = when {
        c in '0'.code..'9'.code -> USX_NIB_NUM
        c in 'a'.code..'f'.code -> USX_NIB_HEX_LOWER
        c in 'A'.code..'F'.code -> USX_NIB_HEX_UPPER
        else -> USX_NIB_NOT
    }

    private fun getBaseCode(c: Int): Int = when {
        c in '0'.code..'9'.code -> (c - '0'.code) shl 4
        c in 'A'.code..'F'.code -> (c - 'A'.code + 10) shl 4
        c in 'a'.code..'f'.code -> (c - 'a'.code + 10) shl 4
        else -> 0
    }

    private fun appendNibbleEscape(out: ByteArray, olen: Int, ol: Int, state: Int): Int {
        var curOl = appendSwitchCode(out, olen, ol, state)
        if (curOl < 0) return curOl
        curOl = appendBits(out, olen, curOl, USX_HCODES[USX_NUM], USX_HCODE_LENS[USX_NUM])
        if (curOl < 0) return curOl
        return appendBits(out, olen, curOl, 0, 2)
    }

    private fun appendFinalBits(
        out: ByteArray, olen: Int, ol: Int,
        state: Int, isAllUpper: Boolean,
    ): Int {
        var curOl = ol
        if (USX_HCODE_LENS[USX_ALPHA] != 0) {
            if (state != USX_NUM) {
                curOl = appendSwitchCode(out, olen, curOl, state)
                if (curOl < 0) return curOl
                curOl = appendBits(out, olen, curOl, USX_HCODES[USX_NUM], USX_HCODE_LENS[USX_NUM])
                if (curOl < 0) return curOl
            }
            curOl = appendBits(out, olen, curOl, USX_VCODES[TERM_CODE and 0x1F], USX_VCODE_LENS[TERM_CODE and 0x1F])
            if (curOl < 0) return curOl
        }
        // Fill last byte with the last bit pattern
        val lastBit = if (curOl == 0) 0
        else if ((out[(curOl - 1) / 8].toInt() shl ((curOl - 1) and 7)) and 0x80 != 0) 0xFF else 0
        return appendBits(out, olen, curOl, lastBit, (8 - curOl % 8) and 7)
    }

    private fun compressInternal(inBytes: ByteArray, len: Int, out: ByteArray, olen: Int): Int {
        var ol = 0
        var l = 0
        var prevUni = 0
        val stateRef = intArrayOf(USX_ALPHA)
        var isAllUpper = false

        // Magic bit
        ol = appendBits(out, olen, ol, MAGIC_BITS, MAGIC_BIT_LEN)
        if (ol < 0) return 0

        while (l < len) {
            // Dict match
            if (USX_HCODE_LENS[USX_DICT] != 0 && l < len - NICE_LEN + 1) {
                val olRef = intArrayOf(ol)
                val ret = matchOccurrence(inBytes, len, l, out, olen, olRef, stateRef)
                if (ret > 0) {
                    ol = olRef[0]
                    l = ret + 1
                    continue
                } else if (ret < 0 && olRef[0] < 0) {
                    return olen + 1
                }
                // ret == -l means no match, continue normally
            }

            var cIn = inBytes[l].toInt() and 0xFF

            // Repeat run detection
            if (l > 0 && len > 4 && l < len - 4 && USX_HCODE_LENS[USX_NUM] != 0) {
                if (cIn == (inBytes[l - 1].toInt() and 0xFF) &&
                    cIn == (inBytes[l + 1].toInt() and 0xFF) &&
                    cIn == (inBytes[l + 2].toInt() and 0xFF) &&
                    cIn == (inBytes[l + 3].toInt() and 0xFF)
                ) {
                    var rptCount = l + 4
                    while (rptCount < len && (inBytes[rptCount].toInt() and 0xFF) == cIn) rptCount++
                    rptCount -= l
                    ol = appendCode(out, olen, ol, RPT_CODE, stateRef)
                    if (ol < 0) return 0
                    ol = encodeCount(out, olen, ol, rptCount - 4)
                    if (ol < 0) return 0
                    l += rptCount
                    continue
                }
            }

            // UUID detection
            if (l <= len - 36 && USX_HCODE_LENS[USX_NUM] != 0) {
                val b = inBytes
                if (b[l + 8].toInt().toChar() == '-' && b[l + 13].toInt().toChar() == '-' &&
                    b[l + 18].toInt().toChar() == '-' && b[l + 23].toInt().toChar() == '-'
                ) {
                    var hexType = USX_NIB_NUM
                    var uidPos = l
                    while (uidPos < l + 36) {
                        val cu = b[uidPos].toInt() and 0xFF
                        if (cu == '-'.code && (uidPos - l == 8 || uidPos - l == 13 || uidPos - l == 18 || uidPos - l == 23)) {
                            uidPos++; continue
                        }
                        val nibType = getNibbleType(cu)
                        if (nibType == USX_NIB_NOT) break
                        if (nibType != USX_NIB_NUM) {
                            if (hexType != USX_NIB_NUM && hexType != nibType) break
                            hexType = nibType
                        }
                        uidPos++
                    }
                    if (uidPos == l + 36) {
                        ol = appendNibbleEscape(out, olen, ol, stateRef[0])
                        if (ol < 0) return 0
                        ol = appendBits(out, olen, ol, if (hexType == USX_NIB_HEX_LOWER) 0xC0 else 0xF0,
                            if (hexType == USX_NIB_HEX_LOWER) 3 else 5)
                        if (ol < 0) return 0
                        for (up in l until l + 36) {
                            val cu = b[up].toInt() and 0xFF
                            if (cu != '-'.code) {
                                ol = appendBits(out, olen, ol, getBaseCode(cu), 4)
                                if (ol < 0) return 0
                            }
                        }
                        l += 36
                        continue
                    }
                }
            }

            // Hex run detection
            if (l < len - 5 && USX_HCODE_LENS[USX_NUM] != 0) {
                var hexType = USX_NIB_NUM
                var hexLen = 0
                while (l + hexLen < len) {
                    val nibType = getNibbleType(inBytes[l + hexLen].toInt() and 0xFF)
                    if (nibType == USX_NIB_NOT) break
                    if (nibType != USX_NIB_NUM) {
                        if (hexType != USX_NIB_NUM && hexType != nibType) break
                        hexType = nibType
                    }
                    hexLen++
                }
                if (hexLen > 10 && hexType == USX_NIB_NUM) hexType = USX_NIB_HEX_LOWER
                if ((hexType == USX_NIB_HEX_LOWER || hexType == USX_NIB_HEX_UPPER) && hexLen > 3) {
                    ol = appendNibbleEscape(out, olen, ol, stateRef[0])
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, if (hexType == USX_NIB_HEX_LOWER) 0x80 else 0xE0,
                        if (hexType == USX_NIB_HEX_LOWER) 2 else 4)
                    if (ol < 0) return 0
                    ol = encodeCount(out, olen, ol, hexLen)
                    if (ol < 0) return 0
                    var hl = hexLen
                    var li = l
                    do {
                        ol = appendBits(out, olen, ol, getBaseCode(inBytes[li].toInt() and 0xFF), 4)
                        if (ol < 0) return 0
                        li++
                    } while (--hl > 0)
                    l = li
                    continue
                }
            }

            // Template detection
            var matchedTemplate = false
            for (ti in USX_TEMPLATES.indices) {
                val tmpl = USX_TEMPLATES[ti] ?: continue
                val rem = tmpl.length
                var j = 0
                while (j < rem && l + j < len) {
                    val ct = tmpl[j]
                    val ci = inBytes[l + j].toInt() and 0xFF
                    val ciChar = ci.toChar()
                    if (ct == 'f' || ct == 'F') {
                        val nibType = getNibbleType(ci)
                        if (nibType != (if (ct == 'f') USX_NIB_HEX_LOWER else USX_NIB_HEX_UPPER) && nibType != USX_NIB_NUM) break
                    } else if (ct == 'r' || ct == 't' || ct == 'o') {
                        val maxDigit = when (ct) { 'r' -> '7'; 't' -> '3'; else -> '1' }
                        if (ciChar < '0' || ciChar > maxDigit) break
                    } else if (ct != ciChar) break
                    j++
                }
                if (j.toFloat() / rem > 0.66f) {
                    val curRem = rem - j
                    ol = appendNibbleEscape(out, olen, ol, stateRef[0])
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, 0, 1)
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, COUNT_CODES[ti] and 0xF8, COUNT_CODES[ti] and 0x07)
                    if (ol < 0) return 0
                    ol = encodeCount(out, olen, ol, curRem)
                    if (ol < 0) return 0
                    for (k in 0 until j) {
                        val ct = tmpl[k]
                        if (ct == 'f' || ct == 'F') {
                            ol = appendBits(out, olen, ol, getBaseCode(inBytes[l + k].toInt() and 0xFF), 4)
                            if (ol < 0) return 0
                        } else if (ct == 'r' || ct == 't' || ct == 'o') {
                            val nbits = when (ct) { 'r' -> 3; 't' -> 2; else -> 1 }
                            ol = appendBits(out, olen, ol, ((inBytes[l + k].toInt() and 0xFF) - '0'.code) shl (8 - nbits), nbits)
                            if (ol < 0) return 0
                        }
                    }
                    l += j
                    matchedTemplate = true
                    break
                }
            }
            if (matchedTemplate) continue

            // Frequent sequence detection
            var matchedFreq = false
            for (fi in 0 until 6) {
                val seq = USX_FREQ_SEQ[fi]
                val seqLen = seq.length
                if (len - seqLen >= 0 && l <= len - seqLen) {
                    val setIdx = USX_FREQ_CODES[fi] ushr 5
                    if (USX_HCODE_LENS[setIdx] != 0) {
                        var match = true
                        for (si in 0 until seqLen) {
                            if ((inBytes[l + si].toInt() and 0xFF) != seq[si].code) { match = false; break }
                        }
                        if (match) {
                            ol = appendCode(out, olen, ol, USX_FREQ_CODES[fi], stateRef)
                            if (ol < 0) return 0
                            l += seqLen
                            matchedFreq = true
                            break
                        }
                    }
                }
            }
            if (matchedFreq) continue

            cIn = inBytes[l].toInt() and 0xFF
            var isUpper = cIn in 'A'.code..'Z'.code

            if (!isUpper) {
                if (isAllUpper) {
                    isAllUpper = false
                    ol = appendSwitchCode(out, olen, ol, stateRef[0])
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                    if (ol < 0) return 0
                    stateRef[0] = USX_ALPHA
                }
            }

            if (isUpper && !isAllUpper) {
                if (stateRef[0] == USX_NUM) {
                    ol = appendSwitchCode(out, olen, ol, stateRef[0])
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                    if (ol < 0) return 0
                    stateRef[0] = USX_ALPHA
                }
                ol = appendSwitchCode(out, olen, ol, stateRef[0])
                if (ol < 0) return 0
                ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                if (ol < 0) return 0
                if (stateRef[0] == USX_DELTA) {
                    stateRef[0] = USX_ALPHA
                    ol = appendSwitchCode(out, olen, ol, stateRef[0])
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                    if (ol < 0) return 0
                }
            }

            val cNext = if (l + 1 < len) inBytes[l + 1].toInt() and 0xFF else 0

            if (cIn in 32..126) {
                if (isUpper && !isAllUpper) {
                    var ll = l + 4
                    while (ll >= l && ll < len) {
                        if ((inBytes[ll].toInt() and 0xFF) < 'A'.code || (inBytes[ll].toInt() and 0xFF) > 'Z'.code) break
                        ll--
                    }
                    if (ll == l - 1) {
                        ol = appendSwitchCode(out, olen, ol, stateRef[0])
                        if (ol < 0) return 0
                        ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                        if (ol < 0) return 0
                        stateRef[0] = USX_ALPHA
                        isAllUpper = true
                    }
                }
                if (stateRef[0] == USX_DELTA && (cIn == ' '.code || cIn == '.'.code || cIn == ','.code)) {
                    val splCode = when (cIn.toChar()) {
                        ',' -> 0xC0; '.' -> 0xE0; ' ' -> 0; else -> -1
                    }
                    val splCodeLen = when (cIn.toChar()) {
                        ',' -> 3; '.' -> 4; ' ' -> 1; else -> 4
                    }
                    if (splCode >= 0) {
                        ol = appendBits(out, olen, ol, UNI_STATE_SPL_CODE, UNI_STATE_SPL_CODE_LEN)
                        if (ol < 0) return 0
                        ol = appendBits(out, olen, ol, splCode, splCodeLen)
                        if (ol < 0) return 0
                        l++
                        continue
                    }
                }
                var cInAdj = cIn - 32
                if (isAllUpper && isUpper) cInAdj += 32
                if (cInAdj == 0) {
                    if (stateRef[0] == USX_NUM) {
                        ol = appendBits(out, olen, ol, USX_VCODES[NUM_SPC_CODE and 0x1F], USX_VCODE_LENS[NUM_SPC_CODE and 0x1F])
                    } else {
                        ol = appendBits(out, olen, ol, USX_VCODES[1], USX_VCODE_LENS[1])
                    }
                    if (ol < 0) return 0
                } else {
                    val code94 = USX_CODE_94[cInAdj - 1]
                    ol = appendCode(out, olen, ol, code94, stateRef)
                    if (ol < 0) return 0
                }
            } else if (cIn == 13 && cNext == 10) {
                ol = appendCode(out, olen, ol, CRLF_CODE, stateRef)
                if (ol < 0) return 0
                l++
            } else if (cIn == 10) {
                if (stateRef[0] == USX_DELTA) {
                    ol = appendBits(out, olen, ol, UNI_STATE_SPL_CODE, UNI_STATE_SPL_CODE_LEN)
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, 0xF0, 4)
                    if (ol < 0) return 0
                } else {
                    ol = appendCode(out, olen, ol, LF_CODE, stateRef)
                    if (ol < 0) return 0
                }
            } else if (cIn == 13) {
                ol = appendCode(out, olen, ol, CR_CODE, stateRef)
                if (ol < 0) return 0
            } else if (cIn == '\t'.code) {
                ol = appendCode(out, olen, ol, TAB_CODE, stateRef)
                if (ol < 0) return 0
            } else {
                val (uni, utf8len) = readUTF8(inBytes, len, l)
                if (uni != 0) {
                    var curL = l + utf8len
                    if (stateRef[0] != USX_DELTA) {
                        val (uni2, _) = readUTF8(inBytes, len, curL)
                        if (uni2 != 0) {
                            if (stateRef[0] != USX_ALPHA) {
                                ol = appendSwitchCode(out, olen, ol, stateRef[0])
                                if (ol < 0) return 0
                                ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                                if (ol < 0) return 0
                            }
                            ol = appendSwitchCode(out, olen, ol, stateRef[0])
                            if (ol < 0) return 0
                            ol = appendBits(out, olen, ol, USX_HCODES[USX_ALPHA], USX_HCODE_LENS[USX_ALPHA])
                            if (ol < 0) return 0
                            ol = appendBits(out, olen, ol, USX_VCODES[1], USX_VCODE_LENS[1])
                            if (ol < 0) return 0
                            stateRef[0] = USX_DELTA
                        } else {
                            ol = appendSwitchCode(out, olen, ol, stateRef[0])
                            if (ol < 0) return 0
                            ol = appendBits(out, olen, ol, USX_HCODES[USX_DELTA], USX_HCODE_LENS[USX_DELTA])
                            if (ol < 0) return 0
                        }
                    }
                    ol = encodeUnicode(out, olen, ol, uni, prevUni)
                    if (ol < 0) return 0
                    prevUni = uni
                    l = curL
                    continue
                } else {
                    // Binary escape
                    var binCount = 1
                    var bi = l + 1
                    while (bi < len) {
                        val (u, _) = readUTF8(inBytes, len, bi)
                        if (u != 0) break
                        val cBi = inBytes[bi].toInt() and 0xFF
                        if (bi < len - 4 &&
                            cBi == (inBytes[bi - 1].toInt() and 0xFF) &&
                            cBi == (inBytes[bi + 1].toInt() and 0xFF) &&
                            cBi == (inBytes[bi + 2].toInt() and 0xFF) &&
                            cBi == (inBytes[bi + 3].toInt() and 0xFF)
                        ) break
                        binCount++
                        bi++
                    }
                    ol = appendNibbleEscape(out, olen, ol, stateRef[0])
                    if (ol < 0) return 0
                    ol = appendBits(out, olen, ol, 0xF8, 5)
                    if (ol < 0) return 0
                    ol = encodeCount(out, olen, ol, binCount)
                    if (ol < 0) return 0
                    var bc = binCount
                    var li = l
                    do {
                        ol = appendBits(out, olen, ol, inBytes[li].toInt() and 0xFF, 8)
                        if (ol < 0) return 0
                        li++
                    } while (--bc > 0)
                    l = li
                    continue
                }
            }
            l++
        }

        // Terminator + fill last byte
        val finalOl = (ol + 7) / 8
        appendFinalBits(out, finalOl, ol, stateRef[0], isAllUpper)
        return finalOl
    }

    // endregion

    // region Decompress -----------------------------------------------------

    private fun readBit(inBytes: ByteArray, bitNo: Int): Int =
        inBytes[bitNo shr 3].toInt() and (0x80 ushr (bitNo % 8))

    private fun read8bitCode(inBytes: ByteArray, lenBits: Int, bitNo: Int): Int {
        val bitPos = bitNo and 0x07
        val charPos = bitNo shr 3
        val lenBytes = lenBits shr 3
        var code = (inBytes[charPos].toInt() and 0xFF) shl bitPos
        val nextPos = charPos + 1
        if (nextPos < lenBytes)
            code = code or ((inBytes[nextPos].toInt() and 0xFF) ushr (8 - bitPos))
        else
            code = code or (0xFF ushr (8 - bitPos))
        return code and 0xFF
    }

    private fun readVCodeIdx(inBytes: ByteArray, lenBits: Int, bitNoRef: IntArray): Int {
        if (bitNoRef[0] >= lenBits) return 99
        val code = read8bitCode(inBytes, lenBits, bitNoRef[0])
        for (i in 0 until 5) {
            if (code <= USX_VSECTIONS[i]) {
                val vcode = USX_VCODE_LOOKUP[USX_VSECTION_POS[i] + ((code and USX_VSECTION_MASK[i]) ushr USX_VSECTION_SHIFT[i])]
                bitNoRef[0] += (vcode ushr 5) + 1
                if (bitNoRef[0] > lenBits) return 99
                return vcode and 0x1F
            }
        }
        return 99
    }

    private val LEN_MASKS = intArrayOf(0x80, 0xC0, 0xE0, 0xF0, 0xF8, 0xFC, 0xFE, 0xFF)

    private fun readHCodeIdx(inBytes: ByteArray, lenBits: Int, bitNoRef: IntArray): Int {
        if (USX_HCODE_LENS[USX_ALPHA] == 0) return USX_ALPHA
        if (bitNoRef[0] >= lenBits) return 99
        val code = read8bitCode(inBytes, lenBits, bitNoRef[0])
        for (codePos in 0 until 5) {
            val clen = USX_HCODE_LENS[codePos]
            if (clen != 0 && (code and LEN_MASKS[clen - 1]) == USX_HCODES[codePos]) {
                bitNoRef[0] += clen
                return codePos
            }
        }
        return 99
    }

    private fun getStepCodeIdx(inBytes: ByteArray, lenBits: Int, bitNoRef: IntArray, limit: Int): Int {
        var idx = 0
        while (bitNoRef[0] < lenBits && readBit(inBytes, bitNoRef[0]) != 0) {
            idx++
            bitNoRef[0]++
            if (idx == limit) return idx
        }
        if (bitNoRef[0] >= lenBits) return 99
        bitNoRef[0]++
        return idx
    }

    private fun getNumFromBits(inBytes: ByteArray, lenBits: Int, bitNo: Int, count: Int): Int {
        var ret = 0
        var bn = bitNo
        var c = count
        while (c-- > 0 && bn < lenBits) {
            ret += if (readBit(inBytes, bn) != 0) 1 shl c else 0
            bn++
        }
        return if (c < 0) ret else -1
    }

    private fun readCount(inBytes: ByteArray, bitNoRef: IntArray, lenBits: Int): Int {
        val idx = getStepCodeIdx(inBytes, lenBits, bitNoRef, 4)
        if (idx == 99) return -1
        if (bitNoRef[0] + COUNT_BIT_LENS[idx] - 1 >= lenBits) return -1
        val count = getNumFromBits(inBytes, lenBits, bitNoRef[0], COUNT_BIT_LENS[idx]) +
            if (idx > 0) COUNT_ADDER[idx - 1] else 0
        bitNoRef[0] += COUNT_BIT_LENS[idx]
        return count
    }

    private fun readUnicode(inBytes: ByteArray, bitNoRef: IntArray, lenBits: Int): Int {
        var idx = getStepCodeIdx(inBytes, lenBits, bitNoRef, 5)
        if (idx == 99) return 0x7FFFFF00 + 99
        if (idx == 5) {
            idx = getStepCodeIdx(inBytes, lenBits, bitNoRef, 4)
            return 0x7FFFFF00 + idx
        }
        if (idx >= 0) {
            val sign = if (bitNoRef[0] < lenBits) readBit(inBytes, bitNoRef[0]) else 0
            bitNoRef[0]++
            if (bitNoRef[0] + UNI_BIT_LEN[idx] - 1 >= lenBits) return 0x7FFFFF00 + 99
            val count = getNumFromBits(inBytes, lenBits, bitNoRef[0], UNI_BIT_LEN[idx]) + UNI_ADDER[idx]
            bitNoRef[0] += UNI_BIT_LEN[idx]
            return if (sign != 0) -count else count
        }
        return 0
    }

    private fun writeUTF8(out: ByteArray, olen: Int, ol: Int, uni: Int): Int {
        var curOl = ol
        if (uni < (1 shl 11)) {
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0xC0 + (uni ushr 6)).toByte()
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0x80 + (uni and 0x3F)).toByte()
        } else if (uni < (1 shl 16)) {
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0xE0 + (uni ushr 12)).toByte()
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0x80 + ((uni ushr 6) and 0x3F)).toByte()
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0x80 + (uni and 0x3F)).toByte()
        } else {
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0xF0 + (uni ushr 18)).toByte()
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0x80 + ((uni ushr 12) and 0x3F)).toByte()
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0x80 + ((uni ushr 6) and 0x3F)).toByte()
            if (curOl >= olen) return olen + 1
            out[curOl++] = (0x80 + (uni and 0x3F)).toByte()
        }
        return curOl
    }

    private fun decodeRepeat(
        inBytes: ByteArray, lenBits: Int,
        out: ByteArray, olen: Int, ol: Int,
        bitNoRef: IntArray,
    ): Int {
        val dictLen = (readCount(inBytes, bitNoRef, lenBits) + NICE_LEN)
        if (dictLen < NICE_LEN) return -1
        val dist = readCount(inBytes, bitNoRef, lenBits) + NICE_LEN - 1
        if (dist < NICE_LEN - 1) return -1
        val left = olen - ol
        if (left <= 0) return olen + 1
        if (ol - dist < 0) return -1
        val copyLen = minOf(left, dictLen)
        System.arraycopy(out, ol - dist, out, ol, copyLen)
        if (left < dictLen) return olen + 1
        return ol + dictLen
    }

    private fun getHexChar(nibble: Int, hexType: Int): Char =
        if (nibble in 0..9) '0' + nibble
        else if (hexType < USX_NIB_HEX_UPPER) 'a' + nibble - 10
        else 'A' + nibble - 10

    private fun decompressInternal(inBytes: ByteArray, len: Int, out: ByteArray, olen: Int): Int {
        var ol = 0
        val bitNoRef = intArrayOf(MAGIC_BIT_LEN) // skip magic bit
        var dstate = USX_ALPHA
        var h = USX_ALPHA
        var isAllUpper = false
        var prevUni = 0
        val lenBits = len shl 3

        while (bitNoRef[0] < lenBits) {
            val origBitNo = bitNoRef[0]
            if (dstate == USX_DELTA || h == USX_DELTA) {
                if (dstate != USX_DELTA) h = dstate
                val delta = readUnicode(inBytes, bitNoRef, lenBits)
                if ((delta ushr 8) == 0x7FFFFF) {
                    val splCodeIdx = delta and 0xFF
                    if (splCodeIdx == 99) break
                    when (splCodeIdx) {
                        0 -> {
                            if (ol >= olen) return olen + 1
                            out[ol++] = ' '.code.toByte()
                            continue
                        }
                        1 -> {
                            h = readHCodeIdx(inBytes, lenBits, bitNoRef)
                            if (h == 99) { bitNoRef[0] = lenBits; continue }
                            if (h == USX_DELTA || h == USX_ALPHA) { dstate = h; continue }
                            if (h == USX_DICT) {
                                val rptRet = decodeRepeat(inBytes, lenBits, out, olen, ol, bitNoRef)
                                if (rptRet < 0) return ol
                                if (rptRet > olen) return olen + 1
                                ol = rptRet
                                h = dstate
                                continue
                            }
                        }
                        2 -> { if (ol >= olen) return olen + 1; out[ol++] = ','.code.toByte(); continue }
                        3 -> { if (ol >= olen) return olen + 1; out[ol++] = '.'.code.toByte(); continue }
                        4 -> { if (ol >= olen) return olen + 1; out[ol++] = 10.toByte(); continue }
                    }
                } else {
                    prevUni += delta
                    val newOl = writeUTF8(out, olen, ol, prevUni)
                    if (newOl > olen) return olen + 1
                    ol = newOl
                }
                if (dstate == USX_DELTA && h == USX_DELTA) continue
            } else {
                h = dstate
            }

            var isUpper = isAllUpper
            var v = readVCodeIdx(inBytes, lenBits, bitNoRef)
            if (v == 99 || h == 99) { bitNoRef[0] = origBitNo; break }

            if (v == 0 && h != USX_SYM) {
                if (bitNoRef[0] >= lenBits) break
                if (h != USX_NUM || dstate != USX_DELTA) {
                    h = readHCodeIdx(inBytes, lenBits, bitNoRef)
                    if (h == 99 || bitNoRef[0] >= lenBits) { bitNoRef[0] = origBitNo; break }
                }
                when (h) {
                    USX_ALPHA -> {
                        if (dstate == USX_ALPHA) {
                            if (USX_HCODE_LENS[USX_ALPHA] == 0 && 0 == (read8bitCode(inBytes, lenBits, bitNoRef[0] - SW_CODE_LEN) and (0xFF shl (8 - (if (isAllUpper) 4 else 6))))) {
                                break // Terminator for preset 1
                            }
                            if (isAllUpper) {
                                isUpper = false; isAllUpper = false; continue
                            }
                            v = readVCodeIdx(inBytes, lenBits, bitNoRef)
                            if (v == 99) { bitNoRef[0] = origBitNo; break }
                            if (v == 0) {
                                h = readHCodeIdx(inBytes, lenBits, bitNoRef)
                                if (h == 99) { bitNoRef[0] = origBitNo; break }
                                if (h == USX_ALPHA) { isAllUpper = true; continue }
                            }
                            isUpper = true
                        } else {
                            dstate = USX_ALPHA; continue
                        }
                    }
                    USX_DICT -> {
                        val rptRet = decodeRepeat(inBytes, lenBits, out, olen, ol, bitNoRef)
                        if (rptRet < 0) break
                        if (rptRet > olen) return olen + 1
                        ol = rptRet
                        continue
                    }
                    USX_DELTA -> { continue }
                    else -> {
                        if (h != USX_NUM || dstate != USX_DELTA)
                            v = readVCodeIdx(inBytes, lenBits, bitNoRef)
                        if (v == 99) { bitNoRef[0] = origBitNo; break }
                        if (h == USX_NUM && v == 0) {
                            // Nibble/template/binary escape
                            val idx = getStepCodeIdx(inBytes, lenBits, bitNoRef, 5)
                            if (idx == 99) break
                            if (idx == 0) {
                                // Template
                                val ti = getStepCodeIdx(inBytes, lenBits, bitNoRef, 4)
                                if (ti >= 5) break
                                val rem0 = readCount(inBytes, bitNoRef, lenBits)
                                if (rem0 < 0) break
                                val tmpl = USX_TEMPLATES[ti] ?: break
                                val tlen = tmpl.length
                                if (rem0 > tlen) break
                                val rem = tlen - rem0
                                var eof = false
                                for (j2 in 0 until rem) {
                                    val ct = tmpl[j2]
                                    if (ct == 'f' || ct == 'r' || ct == 't' || ct == 'o' || ct == 'F') {
                                        val nbits = when (ct) { 'f', 'F' -> 4; 'r' -> 3; 't' -> 2; else -> 1 }
                                        val rawChar = getNumFromBits(inBytes, lenBits, bitNoRef[0], nbits)
                                        if (rawChar < 0) { eof = true; break }
                                        if (ol >= olen) return olen + 1
                                        out[ol++] = getHexChar(rawChar, if (ct == 'f') USX_NIB_HEX_LOWER else USX_NIB_HEX_UPPER).code.toByte()
                                        bitNoRef[0] += nbits
                                    } else {
                                        if (ol >= olen) return olen + 1
                                        out[ol++] = ct.code.toByte()
                                    }
                                }
                                if (eof) break
                            } else if (idx == 5) {
                                // Binary escape
                                var binCount = readCount(inBytes, bitNoRef, lenBits)
                                if (binCount < 0) break
                                if (binCount == 0) break
                                do {
                                    val rawChar = getNumFromBits(inBytes, lenBits, bitNoRef[0], 8)
                                    if (rawChar < 0) break
                                    if (ol >= olen) return olen + 1
                                    out[ol++] = rawChar.toByte()
                                    bitNoRef[0] += 8
                                } while (--binCount > 0)
                                if (binCount > 0) break
                            } else {
                                // Hex run
                                var nibbleCount = 0
                                if (idx == 2 || idx == 4) nibbleCount = 32
                                else {
                                    nibbleCount = readCount(inBytes, bitNoRef, lenBits)
                                    if (nibbleCount < 0) break
                                    if (nibbleCount == 0) break
                                }
                                val hexType = if (idx < 3) USX_NIB_HEX_LOWER else USX_NIB_HEX_UPPER
                                do {
                                    val nibble = getNumFromBits(inBytes, lenBits, bitNoRef[0], 4)
                                    if (nibble < 0) break
                                    if (ol >= olen) return olen + 1
                                    out[ol++] = getHexChar(nibble, hexType).code.toByte()
                                    if ((idx == 2 || idx == 4) && (nibbleCount == 25 || nibbleCount == 21 || nibbleCount == 17 || nibbleCount == 13)) {
                                        if (ol >= olen) return olen + 1
                                        out[ol++] = '-'.code.toByte()
                                    }
                                    bitNoRef[0] += 4
                                } while (--nibbleCount > 0)
                                if (nibbleCount > 0) break
                            }
                            if (dstate == USX_DELTA) h = USX_DELTA
                            continue
                        }
                    }
                }
            }

            if (isUpper && v == 1) { h = USX_DELTA; dstate = USX_DELTA; continue }

            var c = 0
            if (h < 3 && v < 28) c = USX_SETS[h][v]
            if (c in 'a'.code..'z'.code) {
                dstate = USX_ALPHA
                if (isUpper) c -= 32
            } else {
                if (c in '0'.code..'9'.code) {
                    dstate = USX_NUM
                } else if (c == 0) {
                    if (v == 8) {
                        if (ol >= olen) return olen + 1
                        out[ol++] = '\r'.code.toByte()
                        if (ol >= olen) return olen + 1
                        out[ol++] = '\n'.code.toByte()
                    } else if (h == USX_NUM && v == 26) {
                        var count = readCount(inBytes, bitNoRef, lenBits)
                        if (count < 0) break
                        count += 4
                        if (ol <= 0) return 0
                        val rptC = out[ol - 1]
                        while (count-- > 0) {
                            if (ol >= olen) return olen + 1
                            out[ol++] = rptC
                        }
                    } else if (h == USX_SYM && v > 24) {
                        val vi = v - 25
                        val freqSeq = USX_FREQ_SEQ[vi]
                        val left = olen - ol
                        if (left <= 0) return olen + 1
                        val copyLen = minOf(left, freqSeq.length)
                        for (fi in 0 until copyLen) out[ol++] = freqSeq[fi].code.toByte()
                        if (left < freqSeq.length) return olen + 1
                    } else if (h == USX_NUM && v > 22 && v < 26) {
                        val vi = v - (23 - 3)
                        val freqSeq = USX_FREQ_SEQ[vi]
                        val left = olen - ol
                        if (left <= 0) return olen + 1
                        val copyLen = minOf(left, freqSeq.length)
                        for (fi in 0 until copyLen) out[ol++] = freqSeq[fi].code.toByte()
                        if (left < freqSeq.length) return olen + 1
                    } else {
                        break // terminator
                    }
                    if (dstate == USX_DELTA) h = USX_DELTA
                    continue
                }
            }
            if (dstate == USX_DELTA) h = USX_DELTA
            if (ol >= olen) return olen + 1
            out[ol++] = c.toByte()
        }

        return ol
    }

    // endregion
}

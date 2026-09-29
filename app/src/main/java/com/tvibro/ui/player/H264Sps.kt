package com.tvibro.ui.player

/**
 * Minimal H.264 SPS reader.
 *
 * Media3 fills [androidx.media3.common.Format.frameRate] from the container metadata only, and TS
 * carries none, so the badge would stay empty for every live IPTV channel. The bitrate timing in
 * the SPS VUI is the only place the real frame rate is written down, so the payload is decoded
 * here. Nothing else in the app may depend on this: it is a pure function over a byte array and is
 * always executed off the player thread.
 */
internal object H264Sps {

    data class Info(
        val width: Int,
        val height: Int,
        val frameRate: Float?,
        val profileIdc: Int,
        val levelIdc: Int,
        val interlaced: Boolean = false,
    )

    private val HIGH_PROFILES = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Parses the SPS NAL out of an Annex-B [csd] blob (what Media3 stores for AVC video tracks). */
    fun parse(csd: ByteArray?): Info? {
        if (csd == null || csd.size < 8) return null
        for (nal in annexBUnits(csd)) {
            if (nal.isEmpty() || (nal[0].toInt() and 0x1F) != NAL_UNIT_TYPE_SPS) continue
            return runCatching { parseSps(unescape(nal)) }.getOrNull()
        }
        return null
    }

    /** H.264 escapes `00 00 03` inside a NAL; the 0x03 must be dropped before reading bits. */
    private fun unescape(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var outPos = 0
        var i = 0
        while (i < data.size) {
            if (i + 2 < data.size &&
                data[i].toInt() and 0xFF == 0 &&
                data[i + 1].toInt() and 0xFF == 0 &&
                data[i + 2].toInt() and 0xFF == 3
            ) {
                out[outPos++] = 0
                out[outPos++] = 0
                i += 3
            } else {
                out[outPos++] = data[i]
                i++
            }
        }
        return if (outPos == data.size) out else out.copyOf(outPos)
    }

    private fun parseSps(nal: ByteArray): Info? {
        val bits = BitReader(nal)
        bits.skip(8) // nal header

        val profileIdc = bits.read(8)
        bits.skip(8) // constraint_set flags + reserved
        val levelIdc = bits.read(8)
        bits.ue() // seq_parameter_set_id

        val chromaFormatIdc = if (profileIdc in HIGH_PROFILES) {
            val value = bits.ue()
            if (value == 3) bits.skip(1) // separate_colour_plane_flag
            bits.ue() // bit_depth_luma_minus8
            bits.ue() // bit_depth_chroma_minus8
            bits.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (bits.bit() == 1) { // seq_scaling_matrix_present_flag
                val lists = if (value == 3) 12 else 8
                for (i in 0 until lists) {
                    if (bits.bit() == 1) bits.skipScalingList(if (i < 6) 16 else 64)
                }
            }
            value
        } else {
            1
        }

        bits.ue() // log2_max_frame_num_minus4
        when (val orderCntType = bits.ue()) {
            0 -> bits.ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                bits.skip(1) // delta_pic_order_always_zero_flag
                bits.se() // offset_for_non_ref_pic
                bits.se() // offset_for_top_to_bottom_field
                repeat(bits.ue()) { bits.se() } // offset_for_ref_frame
            }
            else -> if (orderCntType < 0) return null
        }
        bits.ue() // max_num_ref_frames
        bits.skip(1) // gaps_in_frame_num_value_allowed_flag

        val widthInMbs = bits.ue() + 1
        val heightInMapUnits = bits.ue() + 1
        val frameMbsOnly = bits.bit()
        if (frameMbsOnly == 0) bits.skip(1) // mb_adaptive_frame_field_flag
        bits.skip(1) // direct_8x8_inference_flag

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (bits.bit() == 1) { // frame_cropping_flag
            cropLeft = bits.ue()
            cropRight = bits.ue()
            cropTop = bits.ue()
            cropBottom = bits.ue()
        }

        val vuiPresent = bits.bit()
        val frameRate = if (vuiPresent == 1) readVuiFrameRate(bits) else null

        // Crop offsets are expressed in sub-macroblock units that depend on the chroma sampling.
        val subWidthC = if (chromaFormatIdc == 3) 1 else 2
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
        val cropUnitY = (if (chromaFormatIdc == 0) 1 else subHeightC) * (if (frameMbsOnly == 0) 2 else 1)
        val width = widthInMbs * 16 - cropUnitX * (cropLeft + cropRight)
        val height = (2 - frameMbsOnly) * heightInMapUnits * 16 - cropUnitY * (cropTop + cropBottom)
        if (width <= 0 || height <= 0) return null

        return Info(width, height, frameRate, profileIdc, levelIdc, interlaced = frameMbsOnly == 0)
    }

    /** VUI timing block: a frame lasts 2 * numUnitsInTick of timeScale, per the H.264 spec. */
    private fun readVuiFrameRate(bits: BitReader): Float? {
        if (bits.bit() == 1) { // aspect_ratio_info_present_flag
            if (bits.read(8) == 255) {
                bits.skip(16)
                bits.skip(16)
            }
        }
        if (bits.bit() == 1) bits.skip(1) // overscan_info_present_flag / overscan_appropriate_flag
        if (bits.bit() == 1) { // video_signal_type_present_flag
            bits.skip(3) // video_format
            bits.skip(1) // video_full_range_flag
            if (bits.bit() == 1) {
                bits.skip(8)
                bits.skip(8)
                bits.skip(8)
            }
        }
        if (bits.bit() == 1) { // chroma_loc_info_present_flag
            bits.ue()
            bits.ue()
        }
        if (bits.bit() == 0) return null // timing_info_present_flag

        val numUnitsInTick = bits.read(32)
        val timeScale = bits.read(32)
        bits.skip(1) // fixed_frame_rate_flag
        if (numUnitsInTick == 0 || timeScale == 0) return null

        val fps = timeScale / (2.0 * numUnitsInTick)
        return fps.toFloat().takeIf { it > 0.1f && it < 500.0f }
    }

    private fun annexBUnits(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() and 0xFF == 0 && data[i + 1].toInt() and 0xFF == 0) {
                when {
                    data[i + 2].toInt() and 0xFF == 1 -> { starts.add(i + 3); i += 3 }
                    i + 3 < data.size && data[i + 2].toInt() and 0xFF == 0 && data[i + 3].toInt() and 0xFF == 1 -> {
                        starts.add(i + 4)
                        i += 4
                    }
                    else -> i++
                }
            } else {
                i++
            }
        }
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.let { it - 3 } ?: data.size
            data.copyOfRange(start, maxOf(start + 1, end))
        }
    }

    private const val NAL_UNIT_TYPE_SPS = 7

    /** Big-endian bit cursor that fails loudly on truncated data instead of inventing zeros. */
    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bit(): Int {
            if (pos >= data.size * 8) throw IllegalStateException("SPS truncated at $pos")
            val value = (data[pos ushr 3].toInt() and 0xFF) ushr (7 - (pos and 7))
            pos++
            return value and 1
        }

        fun read(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun skip(count: Int) {
            repeat(count) { bit() }
        }

        fun ue(): Int {
            var zeros = 0
            while (zeros < 32 && bit() == 0) zeros++
            if (zeros == 0) return 0
            if (zeros >= 32) throw IllegalStateException("SPS exp-golomb overflow")
            return (1 shl zeros) - 1 + read(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 0) -(k shr 1) else (k + 1) shr 1
        }

        fun skipScalingList(size: Int) {
            var last = 8
            var next = 8
            for (i in 0 until size) {
                if (next != 0) {
                    next = (last + se() + 256) % 256
                }
                if (next != 0) last = next
            }
        }
    }
}

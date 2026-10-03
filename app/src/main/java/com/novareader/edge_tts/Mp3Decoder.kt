package com.novareader.edge_tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

object Mp3Decoder {
    fun decode(data: ByteArray): Pair<ByteArray, Int> {
        val file = java.io.File.createTempFile("nova_edge_", ".mp3")
        file.writeBytes(data)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track=i; break }
            if (track < 0) error("No audio track")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start()
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo(); var inputDone=false; var outputDone=false
            while (!outputDone) {
                if (!inputDone) {
                    val idx=codec.dequeueInputBuffer(10000)
                    if(idx>=0){val buf=codec.getInputBuffer(idx)!!;val n=extractor.readSampleData(buf,0);if(n<0){codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}else{codec.queueInputBuffer(idx,0,n,extractor.sampleTime,0);extractor.advance()}}
                }
                when(val idx=codec.dequeueOutputBuffer(info,10000)){
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    else -> if(idx>=0){val buf=codec.getOutputBuffer(idx)!!;buf.position(info.offset);buf.limit(info.offset+info.size);val b=ByteArray(buf.remaining());buf.get(b);out.write(b);codec.releaseOutputBuffer(idx,false);if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)outputDone=true}
                }
            }
            codec.stop(); codec.release(); return out.toByteArray() to rate
        } finally { extractor.release(); file.delete() }
    }
}

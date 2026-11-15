package com.usama.echo;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fades the sound in at the start and out at the end of one part (16-bit audio). */
final class FadeAudioProcessor extends BaseAudioProcessor {
    private final long fadeInMs, fadeOutMs, lengthMs;
    private long framesDone;
    private int rate = 44100, channels = 2;

    /** lengthMs = how long this part plays (after speed changes). */
    FadeAudioProcessor(long fadeInMs, long fadeOutMs, long lengthMs) {
        this.fadeInMs = Math.max(0, fadeInMs);
        this.fadeOutMs = Math.max(0, fadeOutMs);
        this.lengthMs = Math.max(1, lengthMs);
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
        rate = in.sampleRate;
        channels = Math.max(1, in.channelCount);
        return (fadeInMs == 0 && fadeOutMs == 0) ? AudioFormat.NOT_SET : in;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int bytes = input.remaining();
        if (bytes == 0) return;
        ByteBuffer out = replaceOutputBuffer(bytes);
        input.order(ByteOrder.nativeOrder());
        long inFrames = rate * fadeInMs / 1000, outFrames = rate * fadeOutMs / 1000;
        long total = rate * lengthMs / 1000;
        while (input.remaining() >= 2 * channels) {
            float gain = 1f;
            if (inFrames > 0 && framesDone < inFrames) gain = (float) framesDone / inFrames;
            long left = total - framesDone;
            if (outFrames > 0 && left < outFrames) gain = Math.min(gain, Math.max(0f, (float) left / outFrames));
            for (int ch = 0; ch < channels; ch++) {
                short v = input.getShort();
                out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * gain))));
            }
            framesDone++;
        }
        // leftover partial frame (shouldn't happen): pass it on unchanged
        while (input.hasRemaining()) out.put(input.get());
        out.flip();
    }

    @Override
    protected void onFlush() {
        framesDone = 0;
    }

    @Override
    protected void onReset() {
        framesDone = 0;
    }
}

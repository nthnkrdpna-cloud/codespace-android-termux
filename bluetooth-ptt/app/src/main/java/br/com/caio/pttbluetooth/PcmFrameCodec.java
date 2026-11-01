package br.com.caio.pttbluetooth;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/** Length-prefixed PCM frame codec. Wire length is unsigned 16-bit little-endian. */
public final class PcmFrameCodec {
    public static final int MAX_FRAME_BYTES = 2048;

    private PcmFrameCodec() {}

    public static void write(DataOutputStream out, byte[] frame, int length) throws IOException {
        if (out == null || frame == null) throw new IllegalArgumentException("Stream/frame ausente");
        if (length < 2 || length > Math.min(frame.length, MAX_FRAME_BYTES) || (length & 1) != 0)
            throw new IllegalArgumentException("Tamanho de quadro PCM inválido: " + length);
        out.writeByte(length & 0xff);
        out.writeByte((length >>> 8) & 0xff);
        out.write(frame, 0, length);
    }

    public static byte[] read(DataInputStream in) throws IOException {
        int low = in.read();
        if (low < 0) return null;
        int high = in.read();
        if (high < 0) throw new EOFException("Cabeçalho PCM truncado");
        int length = low | (high << 8);
        if (length < 2 || length > MAX_FRAME_BYTES || (length & 1) != 0)
            throw new IOException("Quadro PCM inválido: " + length);
        byte[] frame = new byte[length];
        in.readFully(frame);
        return frame;
    }
}

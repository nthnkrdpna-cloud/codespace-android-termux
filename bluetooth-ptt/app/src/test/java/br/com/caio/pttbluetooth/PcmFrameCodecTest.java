package br.com.caio.pttbluetooth;

import static org.junit.Assert.*;
import java.io.*;
import org.junit.Test;

public class PcmFrameCodecTest {
    @Test public void roundTripUsesLittleEndianLength() throws Exception {
        byte[] payload = new byte[] {1, 2, 3, 4};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PcmFrameCodec.write(new DataOutputStream(bytes), payload, payload.length);
        byte[] wire = bytes.toByteArray();
        assertArrayEquals(new byte[] {4, 0, 1, 2, 3, 4}, wire);
        assertArrayEquals(payload, PcmFrameCodec.read(new DataInputStream(new ByteArrayInputStream(wire))));
    }

    @Test public void cleanEofReturnsNull() throws Exception {
        assertNull(PcmFrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
    }

    @Test(expected = EOFException.class) public void truncatedHeaderFails() throws Exception {
        PcmFrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[] {4})));
    }

    @Test(expected = IOException.class) public void oddLengthIsRejected() throws Exception {
        PcmFrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[] {3, 0, 1, 2, 3})));
    }

    @Test(expected = IllegalArgumentException.class) public void oddOutgoingLengthIsRejected() throws Exception {
        PcmFrameCodec.write(new DataOutputStream(new ByteArrayOutputStream()), new byte[3], 3);
    }
}

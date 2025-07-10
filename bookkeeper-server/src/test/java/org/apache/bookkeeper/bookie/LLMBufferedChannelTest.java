package org.apache.bookkeeper.bookie;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test suite estesa per la classe BufferedChannel.
 * Include test unitari e di integrazione con Mockito.
 * Generata con LLM.
 */
@RunWith(MockitoJUnitRunner.class)
public class LLMBufferedChannelTest {

    private static final int WRITE_CAPACITY = 64;
    private static final int READ_CAPACITY = 64;

    @Mock
    private FileChannel mockFileChannel;

    private ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
    private BufferedChannel bufferedChannel;
    private ByteArrayOutputStream fakeDisk;

    @Before
    public void setup() throws IOException {
        fakeDisk = new ByteArrayOutputStream();

        when(mockFileChannel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            fakeDisk.write(data);
            return data.length;
        });

        when(mockFileChannel.read(any(ByteBuffer.class), anyLong())).thenAnswer(invocation -> {
            ByteBuffer dest = invocation.getArgument(0);
            long pos = invocation.getArgument(1);
            byte[] data = fakeDisk.toByteArray();
            if (pos >= data.length) return -1;
            int len = Math.min(dest.remaining(), data.length - (int) pos);
            dest.put(data, (int) pos, len);
            return len;
        });

        when(mockFileChannel.position()).thenAnswer(i -> (long) fakeDisk.size());

        bufferedChannel = new BufferedChannel(allocator, mockFileChannel, WRITE_CAPACITY, READ_CAPACITY, 128L);
    }

    @After
    public void teardown() throws IOException {
        bufferedChannel.close();
        fakeDisk.close();
    }

    // --- UNIT TESTS ---

    /**
     * Verifica che position() restituisca la posizione corretta dopo una scrittura.
     */
    @Test
    public void testPositionReflectsWrites() throws IOException {
        String data = "abc";
        bufferedChannel.write(toBuf(data));
        assertEquals(data.length(), bufferedChannel.position());
    }

    /**
     * Verifica che getFileChannelPosition() rifletta l’ultima flush.
     */
    @Test
    public void testGetFileChannelPositionAfterFlush() throws IOException {
        bufferedChannel.write(toBuf("test"));
        bufferedChannel.flush();
        assertEquals("Deve coincidere con la posizione del fileChannel", fakeDisk.size(), bufferedChannel.getFileChannelPosition());
    }

    /**
     * Verifica il comportamento di getUnpersistedBytes() dopo la scrittura e flush.
     */
    @Test
    public void testUnpersistedBytesAreTracked() throws IOException {
        bufferedChannel.write(toBuf("flush test"));
        assertTrue(bufferedChannel.getUnpersistedBytes() > 0);
        bufferedChannel.flush();
        bufferedChannel.forceWrite(false);
        assertEquals(0, bufferedChannel.getUnpersistedBytes());
    }

    /**
     * Testa che close() rilasci correttamente il buffer e chiuda il fileChannel.
     */
    @Test
    public void testCloseIsIdempotent() throws IOException {
        bufferedChannel.close();
        bufferedChannel.close(); // Non deve lanciare eccezioni
        verify(mockFileChannel, times(1)).close();
    }

    /**
     * Verifica che getNumOfBytesInWriteBuffer restituisca il numero corretto di byte dopo write().
     */
    @Test
    public void testNumOfBytesInWriteBuffer() throws IOException {
        String content = "buffer size test";
        bufferedChannel.write(toBuf(content));
        assertEquals(content.length(), bufferedChannel.getNumOfBytesInWriteBuffer());
    }

    /**
     * Verifica che clear() svuoti il buffer ma non resetti la posizione.
     */
    @Test
    public void testClearDoesNotResetPosition() throws IOException {
        String content = "clear me";
        bufferedChannel.write(toBuf(content));
        long before = bufferedChannel.position();
        bufferedChannel.clear();
        assertEquals(0, bufferedChannel.getNumOfBytesInWriteBuffer());
        assertEquals(before, bufferedChannel.position());
    }

    // --- INTEGRATION TESTS ---

    /**
     * Verifica che flushAndForceWrite() chiami entrambi i metodi correttamente.
     */
    @Test
    public void testFlushAndForceWrite() throws IOException {
        String data = "important data";
        bufferedChannel.write(toBuf(data));
        bufferedChannel.flushAndForceWrite(true);

        verify(mockFileChannel, times(1)).force(true);
        assertEquals(data, fakeDisk.toString());
    }

    /**
     * Testa che flushAndForceWriteIfRegularFlush venga eseguito solo se doRegularFlushes è true.
     */
    @Test
    public void testFlushAndForceWriteIfRegularFlush_active() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, WRITE_CAPACITY, READ_CAPACITY, 1);
        bc.write(toBuf("trigger"));
        bc.flushAndForceWriteIfRegularFlush(true);
        verify(mockFileChannel, atLeastOnce()).force(anyBoolean());
    }

    /**
     * Testa che forceWrite restituisca la posizione corretta e sincronizzi il file.
     */
    @Test
    public void testForceWriteReturnsCorrectPosition() throws IOException {
        bufferedChannel.write(toBuf("sync now"));
        bufferedChannel.flush();
        long posBeforeForce = bufferedChannel.getFileChannelPosition();
        long forceReturned = bufferedChannel.forceWrite(true);
        assertEquals(posBeforeForce, forceReturned);
    }

    /**
     * Verifica la lettura successiva alla scrittura e flush (integrazione completa).
     */
    @Test
    public void testFullReadAfterFlush() throws IOException {
        String data = "integration read";
        bufferedChannel.write(toBuf(data));
        bufferedChannel.flush();

        ByteBuf readBuf = allocator.buffer(data.length());
        int read = bufferedChannel.read(readBuf, 0, data.length());

        assertEquals(data.length(), read);
        assertEquals(data, readBuf.toString(StandardCharsets.UTF_8));
    }

    // --- Helper Methods ---

    private ByteBuf toBuf(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.UTF_8);
    }
}

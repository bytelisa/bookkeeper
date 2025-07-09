package org.apache.bookkeeper.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.apache.bookkeeper.bookie.BufferedChannel;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class BufferedChannelTest {

    private FileChannel mockFileChannel;
    private ByteBufAllocator allocator;
    private static final int CAPACITY = 128;

    @Before
    public void setUp() throws IOException {
        mockFileChannel = mock(FileChannel.class);
        when(mockFileChannel.position()).thenReturn(0L);
        allocator = UnpooledByteBufAllocator.DEFAULT;
    }

    @Test
    public void testWriteDoesNotFlushWhenBufferIsNotFull() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        ByteBuf data = Unpooled.wrappedBuffer(new byte[CAPACITY / 2]);

        bc.write(data);

        assertEquals(CAPACITY / 2, bc.position());
        assertEquals(CAPACITY / 2, bc.getNumOfBytesInWriteBuffer());
        verify(mockFileChannel, never()).write(any(ByteBuffer.class));
    }

    @Test
    public void testWriteFlushesWhenBufferIsExactlyFull() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        ByteBuf data = Unpooled.wrappedBuffer(new byte[CAPACITY]);

        bc.write(data);

        assertEquals(CAPACITY, bc.position());
        assertEquals(0, bc.getNumOfBytesInWriteBuffer());
        verify(mockFileChannel, times(1)).write(any(ByteBuffer.class));
    }

    @Test
    public void testWriteFlushesWhenBufferOverflows() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        ByteBuf data = Unpooled.wrappedBuffer(new byte[CAPACITY + 10]);

        bc.write(data);

        assertEquals(CAPACITY + 10, bc.position());
        assertEquals(10, bc.getNumOfBytesInWriteBuffer());
        verify(mockFileChannel, times(1)).write(any(ByteBuffer.class));
    }

    @Test
    public void testExplicitFlushWritesDataToFile() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        byte[] testData = "test-data".getBytes();
        bc.write(Unpooled.wrappedBuffer(testData));

        assertEquals(testData.length, bc.getNumOfBytesInWriteBuffer());

        bc.flush();

        assertEquals(0, bc.getNumOfBytesInWriteBuffer());
        ArgumentCaptor<ByteBuffer> captor = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(mockFileChannel, times(1)).write(captor.capture());

        ByteBuffer writtenBuffer = captor.getValue();
        byte[] writtenBytes = new byte[writtenBuffer.remaining()];
        writtenBuffer.get(writtenBytes);
        assertArrayEquals(testData, writtenBytes);
    }

    @Test
    public void testReadFromWriteBuffer() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        byte[] testData = "buffered-data".getBytes();
        bc.write(Unpooled.wrappedBuffer(testData));

        ByteBuf dest = allocator.buffer(testData.length);
        int bytesRead = bc.read(dest, 0, testData.length);

        assertEquals(testData.length, bytesRead);
        byte[] readBytes = new byte[dest.readableBytes()];
        dest.readBytes(readBytes);
        assertArrayEquals(testData, readBytes);
        verify(mockFileChannel, never()).read(any(ByteBuffer.class), anyLong());
    }

    @Test
    public void testReadFromFileChannel() throws IOException {
        byte[] fileData = "data-on-disk".getBytes();
        when(mockFileChannel.read(any(ByteBuffer.class), eq(0L)))
                .thenAnswer(invocation -> {
                    ByteBuffer buffer = invocation.getArgument(0);
                    buffer.put(fileData);
                    return fileData.length;
                });

        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        ByteBuf dest = allocator.buffer(fileData.length);
        int bytesRead = bc.read(dest, 0, fileData.length);

        assertEquals(fileData.length, bytesRead);
        byte[] readBytes = new byte[dest.readableBytes()];
        dest.readBytes(readBytes);
        assertArrayEquals(fileData, readBytes);
        verify(mockFileChannel, times(1)).read(any(ByteBuffer.class), eq(0L));
    }

    @Test
    public void testCloseIsIdempotent() throws IOException {
        BufferedChannel bc = new BufferedChannel(allocator, mockFileChannel, CAPACITY);
        bc.close();
        bc.close(); // Chiamata multipla

        verify(mockFileChannel, times(1)).close();
    }
}
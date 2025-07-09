package org.apache.bookkeeper.client;


import org.junit.Test;
import static org.junit.Assert.*;

public class BookieInfoReaderTest {

    // ========================================================================
    // == Test Suite per BookieInfo                                          ==
    // ========================================================================

    @Test
    public void bookieInfoShouldStoreAndReturnValues() {
        BookieInfoReader.BookieInfo info = new BookieInfoReader.BookieInfo(1000L, 500L);
        assertEquals(1000L, info.getTotalDiskSpace());
        assertEquals(500L, info.getFreeDiskSpace());
    }

    @Test
    public void bookieInfoShouldHandleZeroValues() {
        BookieInfoReader.BookieInfo info = new BookieInfoReader.BookieInfo(0L, 0L);
        assertEquals(0L, info.getTotalDiskSpace());
        assertEquals(0L, info.getFreeDiskSpace());
    }

    @Test
    public void bookieInfoGetWeightShouldReturnFreeDiskSpace() {
        BookieInfoReader.BookieInfo info = new BookieInfoReader.BookieInfo(200L, 123L);
        assertEquals(123L, info.getWeight());
    }


}
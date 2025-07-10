//package org.apache.bookkeeper.client;
//
//import static org.junit.Assert.*;
//import static org.mockito.Mockito.*;
//
//import java.util.*;
//import java.util.concurrent.*;
//
//import org.apache.bookkeeper.client.BookieInfoReader.BookieInfo;
//import org.apache.bookkeeper.conf.ClientConfiguration;
//import org.apache.bookkeeper.net.BookieId;
//import org.apache.bookkeeper.proto.BookieClient;
//import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GetBookieInfoCallback;
//import org.apache.bookkeeper.proto.BookkeeperProtocol;
//import org.junit.Before;
//import org.junit.Test;
//import org.mockito.ArgumentCaptor;
//import org.mockito.invocation.InvocationOnMock;
//import org.mockito.stubbing.Answer;
//
//public class BookieInfoReaderTest {
//
//    private BookieInfoReader reader;
//    private BookKeeper bkMock;
//    private ClientConfiguration confMock;
//    private ScheduledExecutorService scheduler;
//    private BookieClient bookieClient;
//    private BookieWatcher watcher;
//    private PlacementPolicy placementPolicy;
//
//    @Before
//    public void setUp() {
//        bkMock = mock(BookKeeper.class);
//        confMock = mock(ClientConfiguration.class);
//        scheduler = Executors.newSingleThreadScheduledExecutor();
//        bookieClient = mock(BookieClient.class);
//        watcher = mock(BookieWatcher.class);
//        placementPolicy = mock(PlacementPolicy.class);
//
//        when(confMock.getGetBookieInfoIntervalSeconds()).thenReturn(1);
//        when(confMock.getGetBookieInfoRetryIntervalSeconds()).thenReturn(1);
//        when(bkMock.getBookieClient()).thenReturn(bookieClient);
//        when(bkMock.bookieWatcher).thenReturn(watcher);
//        when(bkMock.placementPolicy).thenReturn(placementPolicy);
//
//        reader = new BookieInfoReader(bkMock, confMock, scheduler);
//    }
//
//    /**
//     * Test the BookieInfo constructor and getter methods.
//     */
//    @Test
//    public void testBookieInfoFields() {
//        BookieInfo info = new BookieInfo(1000L, 500L);
//        assertEquals(500L, info.getFreeDiskSpace());
//        assertEquals(1000L, info.getTotalDiskSpace());
//        assertEquals(500L, info.getWeight());
//        assertTrue(info.toString().contains("FreeDiskSpace"));
//    }
//
//    /**
//     * Test retrieval of free disk space when info is available.
//     */
//    @Test
//    public void testGetFreeDiskSpacePresent() {
//        BookieId bookie = BookieId.parse("bookie-1");
//        BookieInfo info = new BookieInfo(1000L, 600L);
//        BookieInfoReader.BookieInfoMap infoMap = getInternalInfoMap(reader);
//        infoMap.gotInfo(bookie, info);
//
//        Optional<Long> freeSpace = reader.getFreeDiskSpace(bookie);
//        assertTrue(freeSpace.isPresent());
//        assertEquals(Long.valueOf(600L), freeSpace.get());
//    }
//
//    /**
//     * Test retrieval of free disk space when info is not available.
//     */
//    @Test
//    public void testGetFreeDiskSpaceAbsent() {
//        BookieId bookie = BookieId.parse("bookie-2");
//        Optional<Long> freeSpace = reader.getFreeDiskSpace(bookie);
//        assertFalse(freeSpace.isPresent());
//    }
//
//    /**
//     * Integration test: simulate BookieClient callback and validate update of bookieInfo.
//     */
//    @Test
//    public void testBookieClientIntegration() throws Exception {
//        BookieId bookie = BookieId.parse("bookie-3");
//        Set<BookieId> bookies = new HashSet<>();
//        bookies.add(bookie);
//        when(watcher.getBookies()).thenReturn(bookies);
//
//        doAnswer(new Answer<Void>() {
//            public Void answer(InvocationOnMock invocation) {
//                GetBookieInfoCallback cb = invocation.getArgument(2);
//                cb.getBookieInfoComplete(0, new BookieInfo(1000L, 900L), invocation.getArgument(3));
//                return null;
//            }
//        }).when(bookieClient).getBookieInfo(eq(bookie), anyLong(), any(GetBookieInfoCallback.class), eq(bookie));
//
//        reader.availableBookiesChanged(bookies);
//        Thread.sleep(100); // Let async tasks complete
//
//        Optional<Long> freeSpace = reader.getFreeDiskSpace(bookie);
//        assertTrue(freeSpace.isPresent());
//        assertEquals(Long.valueOf(900L), freeSpace.get());
//    }
//
//    /**
//     * Helper method to access the internal BookieInfoMap.
//     */
//    private BookieInfoReader.BookieInfoMap getInternalInfoMap(BookieInfoReader reader) {
//        try {
//            java.lang.reflect.Field field = BookieInfoReader.class.getDeclaredField("bookieInfoMap");
//            field.setAccessible(true);
//            return (BookieInfoReader.BookieInfoMap) field.get(reader);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
//}

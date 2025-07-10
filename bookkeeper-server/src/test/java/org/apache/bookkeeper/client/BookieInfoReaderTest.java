package org.apache.bookkeeper.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.net.BookieId;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GetBookieInfoCallback;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BookieInfoReaderTest {

    private BookieInfoReader bookieInfoReader;

    private BookKeeper mockBk;
    @Mock
    private ClientConfiguration mockConf;
    @Mock
    private ScheduledExecutorService mockScheduler;
    @Mock
    private BookieClient mockBookieClient;

    private final BookieId bookie1 = BookieId.parse("bookie1:3181");
    private final BookieId bookie2 = BookieId.parse("bookie2:3181");

    @Before
    public void setUp() {
        // Setup minimale: Usiamo DEEP_STUBS per gestire robustamente le chiamate a catena interne.
        mockBk = mock(BookKeeper.class, RETURNS_DEEP_STUBS);
        // Configuriamo il mock per restituire il bookie client quando richiesto.
        when(mockBk.getBookieClient()).thenReturn(mockBookieClient);

        bookieInfoReader = new BookieInfoReader(mockBk, mockConf, mockScheduler);
    }

    // Helper per simulare la risposta del callback in modo Sincrono e Deterministico.
    private void mockBookieInfoCallback(BookieId bookie, int rc, long total, long free) {
        doAnswer(invocation -> {
            GetBookieInfoCallback callback = invocation.getArgument(2);
            Object context = invocation.getArgument(3);
            BookieInfoReader.BookieInfo info = new BookieInfoReader.BookieInfo(total, free);
            // Eseguiamo il callback immediatamente nello stesso thread del test.
            callback.getBookieInfoComplete(rc, info, context);
            return null;
        }).when(mockBookieClient).getBookieInfo(eq(bookie), anyLong(), any(GetBookieInfoCallback.class), any());
    }

    @Test
    public void testUpdateBookieInfoOnChanges() {
        // Arrange
        // Forziamo lo scheduler a eseguire i task immediatamente.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mockScheduler).submit(any(Runnable.class));

        Set<BookieId> bookies = new HashSet<>();
        bookies.add(bookie1);
        bookies.add(bookie2);

        mockBookieInfoCallback(bookie1, BKException.Code.OK, 1024L, 512L);
        mockBookieInfoCallback(bookie2, BKException.Code.BookieHandleNotAvailableException, 0L, 0L);

        // Act
        bookieInfoReader.availableBookiesChanged(bookies);

        // Assert
        assertEquals(Optional.of(512L), bookieInfoReader.getFreeDiskSpace(bookie1));
        assertFalse("L'info per bookie2 non dovrebbe esistere a causa dell'errore",
                bookieInfoReader.getFreeDiskSpace(bookie2).isPresent());
    }

    @Test
    public void testStaleEntryIsRemovedOnUpdate() {
        // Arrange
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mockScheduler).submit(any(Runnable.class));

        // Popoliamo lo stato con bookie1.
        Set<BookieId> initialBookies = Collections.singleton(bookie1);
        mockBookieInfoCallback(bookie1, BKException.Code.OK, 1024L, 512L);
        bookieInfoReader.availableBookiesChanged(initialBookies);
        assertTrue("L'info per bookie1 deve esistere inizialmente", bookieInfoReader.getFreeDiskSpace(bookie1).isPresent());

        // Act: La nuova lista di bookie è vuota, quindi bookie1 è "stantio".
        bookieInfoReader.availableBookiesChanged(Collections.emptySet());

        // Assert
        assertFalse("L'info per bookie1 deve essere stata rimossa", bookieInfoReader.getFreeDiskSpace(bookie1).isPresent());
    }
}
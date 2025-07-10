package org.apache.bookkeeper.bookie;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.apache.bookkeeper.bookie.BufferedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Test suite per la classe org.apache.bookkeeper.bookie.BufferedChannel.
 * Questa suite utilizza JUnit 4 e Mockito per testare il comportamento della classe
 * in isolamento, simulando le interazioni con il FileChannel sottostante.
 * I test coprono funzionalità di scrittura, lettura, flush, e gestione dei buffer.
 */
@RunWith(MockitoJUnitRunner.class)
public class BufferedChannelTest {

    // Capacità del buffer scelta per facilitare i test sui meccanismi di flush.
    private static final int BUFFER_CAPACITY = 100;

    // Allocatore di buffer Netty.
    private final ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;

    // Mock del FileChannel, dipendenza esterna che verrà simulata.
    @Mock
    private FileChannel mockFileChannel;

    // System Under Test (SUT).
    private BufferedChannel bufferedChannel;

    // Simula il contenuto del file fisico per verificare letture e scritture.
    private ByteArrayOutputStream fileChannelContent;

    /**
     * Imposta l'ambiente di test prima di ogni esecuzione di un metodo di test.
     * Inizializza il mock del FileChannel, il contenuto del "file" simulato e
     * l'istanza di BufferedChannel (SUT).
     * Definisce il comportamento dei metodi del mock (write, read, position, close).
     * @throws IOException se si verifica un errore durante l'inizializzazione.
     */
    @Before
    public void setUp() throws IOException {
        fileChannelContent = new ByteArrayOutputStream();

        // Configura il comportamento del mock per il metodo write.
        // Quando write() viene chiamato, i dati vengono aggiunti al nostro stream in memoria
        // e viene restituito il numero di byte "scritti".
        when(mockFileChannel.write(any(ByteBuffer.class))).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) throws Throwable {
                ByteBuffer srcBuffer = invocation.getArgument(0);
                int bytesToWrite = srcBuffer.remaining();
                if (bytesToWrite > 0) {
                    byte[] data = new byte[bytesToWrite];
                    srcBuffer.get(data);
                    fileChannelContent.write(data);
                }
                return bytesToWrite;
            }
        });

        // Configura il comportamento del mock per il metodo read.
        // Quando read() viene chiamato, i dati vengono letti dal nostro stream in memoria.
        when(mockFileChannel.read(any(ByteBuffer.class), anyLong())).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) throws Throwable {
                ByteBuffer destBuffer = invocation.getArgument(0);
                long position = invocation.getArgument(1);
                byte[] fileData = fileChannelContent.toByteArray();

                if (position >= fileData.length) {
                    return -1; // EOF
                }

                int bytesToRead = Math.min(destBuffer.remaining(), fileData.length - (int) position);
                destBuffer.put(fileData, (int) position, bytesToRead);
                return bytesToRead;
            }
        });

        // Configura la posizione del file channel per riflettere la dimensione del contenuto simulato.
        when(mockFileChannel.position()).thenAnswer(new Answer<Long>() {
            @Override
            public Long answer(InvocationOnMock invocation) {
                return (long) fileChannelContent.size();
            }
        });

        // Crea l'istanza del BufferedChannel da testare.
        bufferedChannel = new BufferedChannel(allocator, mockFileChannel, BUFFER_CAPACITY);
    }

    /**
     * Pulisce le risorse dopo ogni test, assicurando che non ci siano effetti collaterali.
     * @throws IOException se si verifica un errore durante la chiusura.
     */
    @After
    public void tearDown() throws IOException {
        bufferedChannel.close();
        fileChannelContent.close();
    }

    // --- Test per il metodo write() ---

    /**
     * Testa che il metodo write() sollevi una NullPointerException se il buffer sorgente è nullo.
     * Approccio: Black Box (test di input non valido).
     */
    @Test(expected = NullPointerException.class)
    public void testWrite_withNullBuffer_throwsNullPointerException() throws IOException {
        // Quando si tenta di scrivere da un buffer nullo
        bufferedChannel.write(null);
        // Allora ci si aspetta una NullPointerException
    }

    /**
     * Testa che la scrittura di un buffer vuoto non modifichi lo stato del BufferedChannel.
     * Approccio: Black Box (test di caso limite).
     */
    @Test
    public void testWrite_withEmptyBuffer_doesNothing() throws IOException {
        // Quando si scrive un buffer vuoto
        bufferedChannel.write(createByteBuf(""));

        // Allora la posizione e il numero di byte nel buffer di scrittura non devono cambiare
        assertEquals("La posizione non deve cambiare dopo una scrittura vuota", 0, bufferedChannel.position());
        assertEquals("Il buffer di scrittura deve rimanere vuoto", 0, bufferedChannel.getNumOfBytesInWriteBuffer());
        // E non deve avvenire alcuna interazione con il file channel
        verify(mockFileChannel, never()).write(any(ByteBuffer.class));
    }

    /**
     * Testa una singola operazione di scrittura che non riempie il buffer.
     * Verifica che i dati siano nel buffer di scrittura ma non ancora scritti su file.
     * Approccio: White Box (verifica lo stato interno e l'assenza di interazioni).
     */
    @Test
    public void testWrite_singleWriteWithinCapacity() throws IOException {
        String data = "some data";

        // Quando si scrive una quantità di dati inferiore alla capacità del buffer
        bufferedChannel.write(createByteBuf(data));

        // Allora la posizione logica deve essere aggiornata
        assertEquals("La posizione deve corrispondere alla lunghezza dei dati scritti", data.length(), bufferedChannel.position());
        // E i dati devono essere presenti nel buffer di scrittura
        assertEquals("Il buffer di scrittura deve contenere i dati scritti", data.length(), bufferedChannel.getNumOfBytesInWriteBuffer());
        // Ma non deve essere avvenuta alcuna scrittura sul file channel
        verify(mockFileChannel, never()).write(any(ByteBuffer.class));
    }

    /**
     * Testa che una scrittura che eccede la capacità del buffer provochi un'operazione di flush.
     * Verifica che la parte eccedente resti nel buffer.
     * Approccio: White Box (verifica l'interazione di flush con il mock).
     */
    @Test
    public void testWrite_triggersFlushWhenExceedingCapacity() throws IOException {
        String dataChunk1 = generateString(BUFFER_CAPACITY);
        String dataChunk2 = "overflow data";

        // Quando si scrive una quantità di dati che supera la capacità del buffer
        bufferedChannel.write(createByteBuf(dataChunk1 + dataChunk2));

        // Allora la posizione logica deve corrispondere alla lunghezza totale dei dati
        assertEquals("La posizione deve essere la somma delle lunghezze dei dati", (dataChunk1 + dataChunk2).length(), bufferedChannel.position());
        // E solo la parte eccedente deve rimanere nel buffer di scrittura
        assertEquals("Solo i dati di overflow devono rimanere nel buffer", dataChunk2.length(), bufferedChannel.getNumOfBytesInWriteBuffer());
        // Deve essere avvenuta esattamente una scrittura sul file channel
        verify(mockFileChannel, times(1)).write(any(ByteBuffer.class));
        // E il contenuto scritto sul file deve essere esattamente il primo blocco di dati
        assertEquals("I dati scritti su file devono corrispondere al primo chunk", dataChunk1, fileChannelContent.toString());
    }

    // --- Test per il metodo flush() ---

    /**
     * Testa che la chiamata a flush() su un buffer vuoto non provochi alcuna scrittura di dati su file.
     * Approccio: Black Box.
     */
    @Test
    public void testFlush_whenBufferIsEmpty_writesNoData() throws IOException {
        // Quando si chiama flush su un buffer vuoto
        bufferedChannel.flush();

        // Allora nessun dato deve essere stato scritto nel file sottostante.
        assertEquals("Nessun dato deve essere scritto su file", 0, fileChannelContent.size());
    }

    /**
     * Testa che la chiamata a flush() scriva correttamente i contenuti del buffer sul file.
     * Approccio: White Box (verifica l'interazione e il reset dello stato interno).
     */
    @Test
    public void testFlush_whenBufferIsNotEmpty_writesToFile() throws IOException {
        String data = "data to be flushed";
        bufferedChannel.write(createByteBuf(data));

        // Sanity check: i dati sono nel buffer
        assertEquals("Il buffer deve contenere i dati prima del flush", data.length(), bufferedChannel.getNumOfBytesInWriteBuffer());

        // Quando si chiama flush
        bufferedChannel.flush();

        // Allora il buffer di scrittura deve essere svuotato
        assertEquals("Il buffer di scrittura deve essere vuoto dopo il flush", 0, bufferedChannel.getNumOfBytesInWriteBuffer());
        // E il file channel deve aver ricevuto i dati
        verify(mockFileChannel, times(1)).write(any(ByteBuffer.class));
        assertEquals("Il contenuto del file deve corrispondere ai dati scritti", data, fileChannelContent.toString());
        // E la posizione del file channel deve essere aggiornata
        assertEquals("La posizione del file channel deve essere aggiornata", data.length(), bufferedChannel.getFileChannelPosition());
    }

    // --- Test per il metodo read() (Integrazione) ---

    /**
     * Testa la lettura di dati che si trovano esclusivamente nel buffer di scrittura (non ancora "flushed").
     * Approccio: Test di Integrazione (read vs write buffer).
     */
    @Test
    public void testRead_fromWriteBufferOnly() throws IOException {
        String data = "unflushed data";
        bufferedChannel.write(createByteBuf(data));

        ByteBuf dest = allocator.buffer(data.length());

        // Quando si legge dalla posizione dei dati appena scritti
        int bytesRead = bufferedChannel.read(dest, 0, data.length());

        // Allora i byte letti devono corrispondere alla lunghezza dei dati
        assertEquals("Il numero di byte letti deve essere corretto", data.length(), bytesRead);
        // E il contenuto letto deve essere corretto
        assertEquals("I dati letti devono corrispondere a quelli scritti", data, dest.toString(StandardCharsets.UTF_8));
        // E non deve esserci stata alcuna lettura dal file channel fisico
        verify(mockFileChannel, never()).read(any(ByteBuffer.class), anyLong());
    }

    /**
     * Testa la lettura di dati che sono stati scritti e poi "flushed" sul file.
     * Approccio: Test di Integrazione (read vs flushed data).
     */
    @Test
    public void testRead_fromFlushedFileChannelOnly() throws IOException {
        String data = "flushed data";
        bufferedChannel.write(createByteBuf(data));
        bufferedChannel.flush();

        ByteBuf dest = allocator.buffer(data.length());

        // Quando si legge dalla posizione dei dati dopo il flush
        int bytesRead = bufferedChannel.read(dest, 0, data.length());

        // Allora i dati devono essere letti correttamente
        assertEquals("Il numero di byte letti deve essere corretto", data.length(), bytesRead);
        assertEquals("I dati letti devono corrispondere a quelli scritti e 'flushed'", data, dest.toString(StandardCharsets.UTF_8));
        // E deve esserci stata una lettura dal file channel fisico
        verify(mockFileChannel, times(1)).read(any(ByteBuffer.class), eq(0L));
    }

    /**
     * Testa un'operazione di lettura che copre dati sia sul file ("flushed") sia nel buffer di scrittura.
     * Questo è il caso di integrazione più complesso per la lettura.
     * Approccio: Test di Integrazione (read vs flushed + buffered data).
     */
    @Test
    public void testRead_spanningFlushedAndBufferedData() throws IOException {
        String flushedData = generateString(BUFFER_CAPACITY); // Questo causerà un flush
        String bufferedData = "buffered data";
        String totalData = flushedData + bufferedData;

        // Scriviamo dati che eccedono la capacità, causando un flush automatico
        bufferedChannel.write(createByteBuf(totalData));

        ByteBuf dest = allocator.buffer(totalData.length());

        // Quando si tenta di leggere l'intero set di dati
        int bytesRead = bufferedChannel.read(dest, 0, totalData.length());

        // Allora tutti i dati devono essere stati letti correttamente
        assertEquals("Il numero di byte letti deve corrispondere ai dati totali", totalData.length(), bytesRead);
        assertEquals("I dati letti devono corrispondere alla concatenazione di 'flushed' e 'buffered'", totalData, dest.toString(StandardCharsets.UTF_8));

        // E deve essere avvenuta una lettura dal file per la parte "flushed"
        verify(mockFileChannel, times(1)).read(any(ByteBuffer.class), eq(0L));
    }

    /**
     * Testa che un tentativo di leggere oltre la posizione dei dati scritti sollevi una IOException.
     * Approccio: Black Box (test di caso limite/errore).
     */
    @Test(expected = IOException.class)
    public void testRead_beyondWrittenData_throwsIOException() throws IOException {
        String data = "some data";
        bufferedChannel.write(createByteBuf(data));

        // Quando si tenta di leggere oltre la fine dei dati scritti
        ByteBuf dest = allocator.buffer(1);
        bufferedChannel.read(dest, data.length(), 1);
        // Allora ci si aspetta una IOException
    }

    // --- Test per altri metodi ---

    /**
     * Testa che il metodo clear() resetti il buffer di scrittura ma non la posizione assoluta.
     * Approccio: Black Box (verifica dello stato dopo l'operazione).
     */
    @Test
    public void testClear_resetsWriteBufferButNotPosition() throws IOException {
        String data = "some data";
        bufferedChannel.write(createByteBuf(data));

        // Sanity check
        assertEquals(data.length(), bufferedChannel.position());
        assertEquals(data.length(), bufferedChannel.getNumOfBytesInWriteBuffer());

        // Quando si chiama clear
        bufferedChannel.clear();

        // Allora il buffer di scrittura deve essere vuoto
        assertEquals("Il buffer di scrittura deve essere vuoto dopo clear()", 0, bufferedChannel.getNumOfBytesInWriteBuffer());
        // Ma la posizione assoluta non deve essere resettata
        assertEquals("La posizione non deve essere resettata da clear", data.length(), bufferedChannel.position());
    }

    // --- Metodi Helper ---

    /**
     * Crea un ByteBuf Netty a partire da una stringa.
     * @param content La stringa da cui creare il buffer.
     * @return un ByteBuf contenente i dati della stringa in UTF-8.
     */
    private ByteBuf createByteBuf(String content) {
        return Unpooled.copiedBuffer(content, StandardCharsets.UTF_8);
    }

    /**
     * Genera una stringa di lunghezza specificata con caratteri ripetuti.
     * @param length la lunghezza della stringa da generare.
     * @return la stringa generata.
     */
    private String generateString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append('a');
        }
        return sb.toString();
    }
}
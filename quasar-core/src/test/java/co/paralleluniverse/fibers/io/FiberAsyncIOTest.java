/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers.io;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableRunnable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import static java.nio.file.StandardOpenOption.*;

import jsr166e.ForkJoinPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;
import org.junit.Ignore;

/**
 *
 * @author pron
 */
public class FiberAsyncIOTest {
    private static final int PORT = 1234;
    private static final Charset charset = Charset.forName("UTF-8");
    private static final CharsetEncoder encoder = charset.newEncoder();
    private static final CharsetDecoder decoder = charset.newDecoder();
    private ForkJoinPool fjPool;

    public FiberAsyncIOTest() {
        fjPool = new ForkJoinPool(4, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testFiberAsyncSocket() throws Exception {
        final Fiber server = new Fiber(fjPool, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution {
                try (FiberServerSocketChannel socket = FiberServerSocketChannel.open().bind(new InetSocketAddress(PORT));
                        FiberSocketChannel ch = socket.accept()) {

                    ByteBuffer buf = ByteBuffer.allocateDirect(1024);

                    // long-typed reqeust/response
                    int n = ch.read(buf);

                    assertThat(n, is(8)); // we assume the message is sent in a single packet

                    buf.flip();
                    long req = buf.getLong();

                    assertThat(req, is(12345678L));

                    buf.clear();
                    long res = 87654321L;
                    buf.putLong(res);
                    buf.flip();

                    n = ch.write(buf);

                    assertThat(n, is(8));

                    // String reqeust/response
                    buf.clear();
                    n = ch.read(buf); // we assume the message is sent in a single packet

                    buf.flip();
                    String req2 = decoder.decode(buf).toString();

                    assertThat(req2, is("my request"));

                    String res2 = "my response";
                    n = ch.write(encoder.encode(CharBuffer.wrap(res2)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
        });

        final Fiber client = new Fiber(fjPool, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution {
                try (FiberSocketChannel ch = FiberSocketChannel.open(new InetSocketAddress(PORT))) {
                    ByteBuffer buf = ByteBuffer.allocateDirect(1024);

                    // long-typed reqeust/response
                    long req = 12345678L;
                    buf.putLong(req);
                    buf.flip();

                    int n = ch.write(buf);

                    assertThat(n, is(8));

                    buf.clear();
                    n = ch.read(buf);

                    assertThat(n, is(8)); // we assume the message is sent in a single packet

                    buf.flip();
                    long res = buf.getLong();

                    assertThat(res, is(87654321L));

                    // String reqeust/response
                    String req2 = "my request";
                    n = ch.write(encoder.encode(CharBuffer.wrap(req2)));

                    buf.clear();
                    n = ch.read(buf); // we assume the message is sent in a single packet

                    buf.flip();
                    String res2 = decoder.decode(buf).toString();

                    assertThat(res2, is("my response"));

                    // verify that the server has closed the socket
                    buf.clear();
                    n = ch.read(buf);

                    assertThat(n, is(-1));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        server.start();
        Thread.sleep(100);
        client.start();

        client.join();
        server.join();
    }

    @Test
    public void testFiberAsyncFile() throws Exception {
        new Fiber(fjPool, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution {
                try (FiberFileChannel ch = FiberFileChannel.open(Paths.get(System.getProperty("user.home"), "fibertest.bin"), READ, WRITE, CREATE, TRUNCATE_EXISTING)) {
                    ByteBuffer buf = ByteBuffer.allocateDirect(1024);
                    
                    String text = "this is my text blahblah";
                    ch.write(encoder.encode(CharBuffer.wrap(text)));
                    
                    ch.position(0);
                    ch.read(buf);
                    
                    buf.flip();
                    String read = decoder.decode(buf).toString();
                    
                    assertThat(read, equalTo(text));
                    
                    buf.clear();
                    
                    ch.position(5);
                    ch.read(buf);
                    
                    buf.flip();
                    read = decoder.decode(buf).toString();
                    
                    assertThat(read, equalTo(text.substring(5)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
        }).start().join();
    }
}

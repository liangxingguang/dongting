/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.net;

import com.github.dtprj.dongting.common.CloseUtil;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.pb.DtFrame;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author huangli
 */
public class NioClientTest {
    private static final DtLog log = DtLogs.getLogger(NioClientTest.class);

    private static class BioServer implements AutoCloseable {
        private ServerSocket ss;
        private Socket s;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile boolean stop;
        private Thread readThread;
        private Thread writeThread;
        private ArrayBlockingQueue<DtFrame.Frame> queue = new ArrayBlockingQueue<>(100);
        private long sleep;

        public BioServer(int port) throws Exception {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            new Thread(this::runAcceptThread).start();
        }

        public void runAcceptThread() {
            try {
                s = ss.accept();
                s.setSoTimeout(1000);
                readThread = new Thread(this::runReadThread);
                writeThread = new Thread(this::runWriteThread);
                readThread.start();
                writeThread.start();
            } catch (Throwable e) {
                log.error("", e);
            }
        }

        public void runReadThread() {
            try {
                in = new DataInputStream(s.getInputStream());
                while (!stop) {
                    int len = in.readInt();
                    byte[] data = new byte[len];
                    in.readFully(data);
                    DtFrame.Frame pbFrame = DtFrame.Frame.parseFrom(data);
                    queue.put(pbFrame);
                }
            } catch (EOFException e) {
            } catch (Exception e) {
                log.error("", e);
            }
        }

        public void runWriteThread() {
            try {
                out = new DataOutputStream(s.getOutputStream());
                while (!stop) {
                    if (queue.size() > 1) {
                        ArrayList<DtFrame.Frame> list = new ArrayList<>();
                        queue.drainTo(list);
                        // shuffle
                        for (int i = list.size() - 1; i >= 0; i--) {
                            writeFrame(out, list.get(i));
                        }
                    } else {
                        DtFrame.Frame frame = queue.take();
                        writeFrame(out, frame);
                    }
                }
            } catch (InterruptedException e) {
            } catch (Exception e) {
                log.error("", e);
            }
        }

        private void writeFrame(DataOutputStream out, DtFrame.Frame frame) throws Exception {
            frame = DtFrame.Frame.newBuilder().mergeFrom(frame)
                    .setFrameType(CmdType.TYPE_RESP)
                    .build();
            byte[] bs = frame.toByteArray();
            Thread.sleep(sleep);
            out.writeInt(bs.length);
            out.write(bs);
        }

        @Override
        public void close() throws Exception {
            if (stop) {
                return;
            }
            stop = true;
            in.close();
            out.close();
            ss.close();
        }
    }

    @Test
    public void simpleTest() throws Exception {
        BioServer server = null;
        NioClient client = null;
        try {
            server = new BioServer(9000);
            NioClientConfig c = new NioClientConfig();
            c.setHostPorts(Collections.singletonList(new HostPort("127.0.0.1", 9000)));
            client = new NioClient(c);
            client.start();
            client.waitStart();
            simpleTest(client, 100);
        } finally {
            CloseUtil.close(client, server);
        }
    }

    @Test
    public void multiServerTest() throws Exception {
        BioServer server1 = null;
        BioServer server2 = null;
        NioClient client = null;
        try {
            server1 = new BioServer(9000);
            server2 = new BioServer(9001);
            NioClientConfig c = new NioClientConfig();
            c.setHostPorts(Arrays.asList(new HostPort("127.0.0.1", 9000), new HostPort("127.0.0.1", 9001)));
            client = new NioClient(c);
            client.start();
            client.waitStart();
            simpleTest(client, 100);
        } finally {
            CloseUtil.close(client, server1, server2);
        }
    }

    private static void simpleTest(NioClient client, long timeMillis) throws Exception {
        int seq = 0;
        final int maxBodySize = 5000;
        Random r = new Random();
        DtTime time = new DtTime();
        do {
            sendSync(seq++, maxBodySize, client, 500);
        } while (time.elapse(TimeUnit.MILLISECONDS) < timeMillis);
        time = new DtTime();
        CompletableFuture<Integer> successCount = new CompletableFuture<>();
        successCount.complete(0);
        int expectCount = 0;
        do {
            ByteBufferWriteFrame wf = new ByteBufferWriteFrame();
            wf.setCommand(Commands.CMD_PING);
            wf.setFrameType(CmdType.TYPE_REQ);
            wf.setSeq(seq++);
            byte[] bs = new byte[r.nextInt(maxBodySize)];
            r.nextBytes(bs);
            wf.setBody(ByteBuffer.wrap(bs));
            CompletableFuture<ReadFrame> f = client.sendRequest(wf,
                    ByteBufferDecoder.INSTANCE, new DtTime(1, TimeUnit.SECONDS));
            expectCount++;
            successCount = successCount.thenCombine(f, (currentCount, rf) -> {
                try {
                    assertEquals(wf.getSeq(), rf.getSeq());
                    assertEquals(CmdType.TYPE_RESP, rf.getFrameType());
                    assertEquals(CmdCodes.SUCCESS, rf.getRespCode());
                    assertArrayEquals(bs, ((ByteBuffer) rf.getBody()).array());
                    return currentCount + 1;
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
        } while (time.elapse(TimeUnit.MILLISECONDS) < timeMillis);
        int v = successCount.get(1, TimeUnit.SECONDS);
        assertTrue(v > 0);
        assertEquals(expectCount, v);
    }

    private static void sendSync(int seq, int maxBodySize, NioClient client, long timeoutMillis) throws Exception {
        ByteBufferWriteFrame wf = new ByteBufferWriteFrame();
        wf.setCommand(Commands.CMD_PING);
        wf.setFrameType(CmdType.TYPE_REQ);
        wf.setSeq(seq);
        byte[] bs = new byte[ThreadLocalRandom.current().nextInt(maxBodySize)];
        ThreadLocalRandom.current().nextBytes(bs);
        wf.setBody(ByteBuffer.wrap(bs));
        CompletableFuture<ReadFrame> f = client.sendRequest(wf,
                ByteBufferDecoder.INSTANCE, new DtTime(timeoutMillis, TimeUnit.MILLISECONDS));
        ReadFrame rf = f.get(5000, TimeUnit.MILLISECONDS);
        assertEquals(wf.getSeq(), rf.getSeq());
        assertEquals(CmdType.TYPE_RESP, rf.getFrameType());
        assertEquals(CmdCodes.SUCCESS, rf.getRespCode());
        assertArrayEquals(bs, ((ByteBuffer) rf.getBody()).array());
    }

    private static void sendSyncByPeer(int seq, int maxBodySize, NioClient client,
                                       Peer peer, long timeoutMillis) throws Exception {
        ByteBufferWriteFrame wf = new ByteBufferWriteFrame();
        wf.setCommand(Commands.CMD_PING);
        wf.setFrameType(CmdType.TYPE_REQ);
        wf.setSeq(seq);
        byte[] bs = new byte[ThreadLocalRandom.current().nextInt(maxBodySize)];
        ThreadLocalRandom.current().nextBytes(bs);
        wf.setBody(ByteBuffer.wrap(bs));
        CompletableFuture<ReadFrame> f = client.sendRequest(peer, wf,
                ByteBufferDecoder.INSTANCE, new DtTime(timeoutMillis, TimeUnit.MILLISECONDS));
        ReadFrame rf = f.get(5000, TimeUnit.MILLISECONDS);
        assertEquals(wf.getSeq(), rf.getSeq());
        assertEquals(CmdType.TYPE_RESP, rf.getFrameType());
        assertEquals(CmdCodes.SUCCESS, rf.getRespCode());
        assertArrayEquals(bs, ((ByteBuffer) rf.getBody()).array());
    }

    @Test
    public void connectFailTest() {
        NioClientConfig c = new NioClientConfig();
        c.setHostPorts(Collections.singletonList(new HostPort("127.0.0.1", 23245)));
        c.setConnectTimeoutMillis(5);
        NioClient client = new NioClient(c);
        client.start();
        Assertions.assertThrows(NetException.class, () -> client.waitStart());
        client.stop();
    }

    @Test
    public void reconnectTest() throws Exception {
        BioServer server1 = null;
        BioServer server2 = null;
        NioClient client = null;
        try {
            server1 = new BioServer(9000);
            server2 = new BioServer(9001);
            NioClientConfig c = new NioClientConfig();
            c.setConnectTimeoutMillis(50);
            HostPort hp1 = new HostPort("127.0.0.1", 9000);
            HostPort hp2 = new HostPort("127.0.0.1", 9001);
            c.setHostPorts(Arrays.asList(hp1, hp2));
            client = new NioClient(c);
            client.start();
            client.waitStart();
            int seq = 0;
            for (int i = 0; i < 10; i++) {
                sendSync(seq++, 5000, client, 500);
            }
            server1.close();
            for (int i = 0; i < 10; i++) {
                sendSync(seq++, 5000, client, 500);
            }

            Peer p1 = null;
            Peer p2 = null;
            for (Peer peer : client.getPeers()) {
                if (hp1.equals(peer.getEndPoint())) {
                    assertNull(peer.getDtChannel());
                    p1 = peer;
                } else {
                    assertNotNull(peer.getDtChannel());
                    p2 = peer;
                }
            }

            try {
                sendSyncByPeer(seq++, 5000, client, p1, 500);
            } catch (ExecutionException e) {
                assertEquals(NetException.class, e.getCause().getClass());
            }
            sendSyncByPeer(seq++, 5000, client, p2, 500);

            try {
                client.reconnect(p1).get(20, TimeUnit.MILLISECONDS);
                fail();
            } catch (TimeoutException | ExecutionException e) {
            }
            server1 = new BioServer(9000);
            client.reconnect(p1).get(200, TimeUnit.MILLISECONDS);
            sendSyncByPeer(seq++, 5000, client, p1, 500);

            try {
                client.reconnect(p1).get(20, TimeUnit.MILLISECONDS);
                fail();
            } catch (ExecutionException e) {
                assertEquals(NetException.class, e.getCause().getClass());
            }

            Peer p = new Peer(null, null);
            try {
                client.reconnect(p);
                fail();
            } catch (IllegalArgumentException e) {
            }
        } finally {
            CloseUtil.close(client, server1, server2);
        }
    }
}

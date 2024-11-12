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
package com.github.dtprj.dongting.dtkv.server;

import com.github.dtprj.dongting.common.ByteArray;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.dtkv.KvCodes;
import com.github.dtprj.dongting.dtkv.KvResult;
import com.github.dtprj.dongting.fiber.BaseFiberTest;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftInput;
import com.github.dtprj.dongting.raft.sm.SnapshotInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author huangli
 */
public class DtKVTest extends BaseFiberTest {
    private DtKV kv;
    int ver;

    @BeforeEach
    void setUp() {
        ver = 1;
        kv = createAndStart();
    }

    private DtKV createAndStart() {
        RaftGroupConfigEx groupConfig = new RaftGroupConfigEx(0, "1", "");
        groupConfig.setFiberGroup(fiberGroup);
        groupConfig.setTs(fiberGroup.getDispatcher().getTs());
        KvConfig kvConfig = new KvConfig();
        kvConfig.setUseSeparateExecutor(false);
        kvConfig.setInitMapCapacity(16);
        DtKV kv = new DtKV(groupConfig, kvConfig);
        kv.start();
        return kv;
    }

    @AfterEach
    void tearDown() {
        kv.stop(new DtTime(1, TimeUnit.SECONDS));
    }

    private KvResult put(int index, String key, String value) {
        RaftInput i = new RaftInput(DtKV.BIZ_TYPE_PUT, new ByteArray(key.getBytes()),
                new ByteArray(value.getBytes()), new DtTime(1, TimeUnit.SECONDS), false);
        FiberFuture<Object> f = kv.exec(index, i);
        assertTrue(f.isDone());
        return (KvResult) f.getResult();
    }

    private KvResult remove(int index, String key) {
        RaftInput i = new RaftInput(DtKV.BIZ_TYPE_REMOVE, new ByteArray(key.getBytes()),
                null, new DtTime(1, TimeUnit.SECONDS), false);
        FiberFuture<Object> f = kv.exec(index, i);
        assertTrue(f.isDone());
        return (KvResult) f.getResult();
    }

    private KvResult mkdir(int index, String key) {
        RaftInput i = new RaftInput(DtKV.BIZ_TYPE_MKDIR, new ByteArray(key.getBytes()),
                null, new DtTime(1, TimeUnit.SECONDS), false);
        FiberFuture<Object> f = kv.exec(index, i);
        assertTrue(f.isDone());
        return (KvResult) f.getResult();
    }

    private KvResult get(String key) {
        return kv.get(new ByteArray(key.getBytes()));
    }

    private KvResult get(DtKV dtkv, String key) {
        return dtkv.get(new ByteArray(key.getBytes()));
    }

    private String getStr(DtKV dtkv, String key) {
        return new String(dtkv.get(new ByteArray(key.getBytes())).getNode().getData());
    }

    private Pair<Integer, List<KvResult>> list(String key) {
        return kv.list(new ByteArray(key.getBytes()));
    }

    @Test
    void simpleTest() throws Exception {
        doInFiber(() -> {
            assertEquals(KvCodes.CODE_SUCCESS, mkdir(ver++, "parent").getBizCode());
            assertEquals(KvCodes.CODE_SUCCESS, put(ver++, "parent.child1", "v1").getBizCode());
            assertEquals(KvCodes.CODE_SUCCESS, get("parent.child1").getBizCode());
            assertEquals("v1", new String(get("parent.child1").getNode().getData()));
            assertEquals(KvCodes.CODE_SUCCESS, remove(ver++, "parent.child1").getBizCode());
            assertEquals(KvCodes.CODE_SUCCESS, list("").getLeft());
            assertEquals(1, list("").getRight().size());
        });
    }

    private KvSnapshot takeSnapshot() {
        long lastIndex = ver - 1;
        int lastTerm = 1;
        SnapshotInfo si = new SnapshotInfo(lastIndex, lastTerm, null, null, null, null, 0);
        return (KvSnapshot) kv.takeSnapshot(si);
    }

    private DtKV copyTo(KvSnapshot s) {
        DtKV kv2 = createAndStart();
        long offset = 0;
        long lastIndex = s.getSnapshotInfo().getLastIncludedIndex();
        int lastTerm = s.getSnapshotInfo().getLastIncludedTerm();
        ByteBuffer buf = ByteBuffer.allocate(64);
        while (true) {
            buf.clear();
            FiberFuture<Integer> f1 = s.readNext(buf);
            assertTrue(f1.isDone());
            buf.flip();
            assertEquals(f1.getResult(), buf.remaining());
            boolean done = f1.getResult() == 0;
            FiberFuture<Void> f2 = kv2.installSnapshot(lastIndex, lastTerm, offset, done, done ? null : buf);
            offset += f1.getResult();
            assertTrue(f2.isDone());
            assertNull(f2.getEx());
            if (done) {
                break;
            }
        }
        return kv2;
    }

    @Test
    void testSnapshot() throws Exception {
        doInFiber(() -> {
            mkdir(ver++, "d1");
            mkdir(ver++, "d1.dd1");
            mkdir(ver++, "d1.dd2");
            mkdir(ver++, "d1.dd1.ddd1");

            put(ver++, "k1", "k1_v");
            put(ver++, "k2", "k2_v");
            put(ver++, "d1.k1", "d1.k1_v");
            put(ver++, "d1.k2", "d1.k2_v");
            put(ver++, "d1.dd1.k1", "d1.dd1.k1_v");
            put(ver++, "d1.dd1.k2", "d1.dd1.k2_v");
            put(ver++, "d1.dd1.ddd1.k1", "d1.dd1.ddd1.k1_v");
            put(ver++, "d1.dd1.ddd1.k2", "d1.dd1.ddd1.k2_v");
            put(ver++, "d1.dd2.k1", "d1.dd2.k1_v");
            put(ver++, "d1.dd2.k2", "d1.dd2.k2_v");
            for (int i = 0; i < 50; i++) {
                put(ver++, "key" + i, "value" + i);
            }
            KvSnapshot s1 = takeSnapshot();

            put(ver++, "d1.k2", "d1.k2_v2");
            KvSnapshot s2 = takeSnapshot();

            remove(ver++, "k1");
            mkdir(ver++, "k1");
            put(ver++, "k1.k1", "k1.k1_v");
            remove(ver++, "d1.dd2.k1");
            remove(ver++, "d1.dd2.k2");
            remove(ver++, "d1.dd2");
            put(ver++, "d1.dd2", "d1.dd2_v");
            KvSnapshot s3 = takeSnapshot();

            {
                DtKV newKv = copyTo(s1);
                assertEquals("k1_v", getStr(newKv, "k1"));
                assertEquals("k2_v", getStr(newKv, "k2"));
                assertEquals("d1.k1_v", getStr(newKv, "d1.k1"));
                assertEquals("d1.k2_v", getStr(newKv, "d1.k2"));
                assertEquals("d1.dd1.k1_v", getStr(newKv, "d1.dd1.k1"));
                assertEquals("d1.dd1.k2_v", getStr(newKv, "d1.dd1.k2"));
                assertEquals("d1.dd1.ddd1.k1_v", getStr(newKv, "d1.dd1.ddd1.k1"));
                assertEquals("d1.dd1.ddd1.k2_v", getStr(newKv, "d1.dd1.ddd1.k2"));
                assertEquals("d1.dd2.k1_v", getStr(newKv, "d1.dd2.k1"));
                assertEquals("d1.dd2.k2_v", getStr(newKv, "d1.dd2.k2"));
                for (int i = 0; i < 50; i++) {
                    assertEquals("value" + i, getStr(newKv, "key" + i));
                }
            }
            {
                DtKV newKv = copyTo(s2);
                assertEquals("d1.k2_v2", getStr(newKv, "d1.k2"));

                assertEquals("k1_v", getStr(newKv, "k1"));
                assertEquals("k2_v", getStr(newKv, "k2"));
                assertEquals("d1.k1_v", getStr(newKv, "d1.k1"));
                assertEquals("d1.dd1.k1_v", getStr(newKv, "d1.dd1.k1"));
                assertEquals("d1.dd1.k2_v", getStr(newKv, "d1.dd1.k2"));
                assertEquals("d1.dd1.ddd1.k1_v", getStr(newKv, "d1.dd1.ddd1.k1"));
                assertEquals("d1.dd1.ddd1.k2_v", getStr(newKv, "d1.dd1.ddd1.k2"));
                assertEquals("d1.dd2.k1_v", getStr(newKv, "d1.dd2.k1"));
                assertEquals("d1.dd2.k2_v", getStr(newKv, "d1.dd2.k2"));
                for (int i = 0; i < 50; i++) {
                    assertEquals("value" + i, getStr(newKv, "key" + i));
                }
            }
            {
                DtKV newKv = copyTo(s3);
                assertTrue(get(newKv, "k1").getNode().isDir());
                assertEquals("k1.k1_v", getStr(newKv, "k1.k1"));
                assertEquals(KvCodes.CODE_NOT_FOUND, get(newKv, "d1.dd2.k1").getBizCode());
                assertEquals(KvCodes.CODE_NOT_FOUND, get(newKv, "d1.dd2.k2").getBizCode());
                assertEquals("d1.dd2_v", getStr(newKv, "d1.dd2"));

                assertEquals("d1.k2_v2", getStr(newKv, "d1.k2"));

                assertEquals("k2_v", getStr(newKv, "k2"));
                assertEquals("d1.k1_v", getStr(newKv, "d1.k1"));
                assertEquals("d1.dd1.k1_v", getStr(newKv, "d1.dd1.k1"));
                assertEquals("d1.dd1.k2_v", getStr(newKv, "d1.dd1.k2"));
                assertEquals("d1.dd1.ddd1.k1_v", getStr(newKv, "d1.dd1.ddd1.k1"));
                assertEquals("d1.dd1.ddd1.k2_v", getStr(newKv, "d1.dd1.ddd1.k2"));
            }

        });
    }
}

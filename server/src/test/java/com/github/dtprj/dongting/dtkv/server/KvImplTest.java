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
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.dtkv.KvCodes;
import com.github.dtprj.dongting.dtkv.KvResult;
import com.github.dtprj.dongting.raft.sm.SnapshotInfo;
import com.github.dtprj.dongting.raft.test.TestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author huangli
 */
class KvImplTest {

    private KvImpl kv;
    private Timestamp ts = new Timestamp();
    private int oldGcItems;

    @BeforeEach
    void setUp() {
        ts = new Timestamp();
        kv = new KvImpl(ts, 0, 16, 0.75f);
        oldGcItems = KvImpl.GC_ITEMS;
    }

    @AfterEach
    void tearDown() {
        KvImpl.GC_ITEMS = oldGcItems;
    }

    private static ByteArray ba(String str) {
        return new ByteArray(str.getBytes());
    }

    @Test
    void testGetPut() {
        KvResult r = kv.get(ba("key1"));
        assertEquals(KvCodes.CODE_NOT_FOUND, r.getBizCode());
        assertNull(r.getNode());

        assertEquals(KvCodes.CODE_SUCCESS, kv.put(1, ba("key1"), "value1".getBytes()).getBizCode());
        r = kv.get(ba("key1"));
        assertEquals(KvCodes.CODE_SUCCESS, r.getBizCode());
        assertArrayEquals("value1".getBytes(), r.getNode().getData());
        assertFalse(r.getNode().isDir());
        assertEquals(ts.getWallClockMillis(), r.getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), r.getNode().getUpdateTime());
        assertEquals(1, r.getNode().getCreateIndex());
        assertEquals(1, r.getNode().getUpdateIndex());

        TestUtil.updateTimestamp(ts, ts.getNanoTime() + 1, ts.getWallClockMillis() + 1);
        assertEquals(KvCodes.CODE_SUCCESS_OVERWRITE, kv.put(2, ba("key1"), "value2".getBytes()).getBizCode());
        r = kv.get(ba("key1"));
        assertEquals(KvCodes.CODE_SUCCESS, r.getBizCode());
        assertArrayEquals("value2".getBytes(), r.getNode().getData());
        assertFalse(r.getNode().isDir());
        assertEquals(ts.getWallClockMillis() - 1, r.getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), r.getNode().getUpdateTime());
        assertEquals(1, r.getNode().getCreateIndex());
        assertEquals(2, r.getNode().getUpdateIndex());

        assertEquals(KvCodes.CODE_VALUE_EXISTS, kv.mkdir(3, ba("key1")).getBizCode());
    }

    @Test
    void testGetPutInDir() {
        kv.mkdir(1, ba("parent"));
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("parent.key1")).getBizCode());

        TestUtil.updateTimestamp(ts, ts.getNanoTime() + 1, ts.getWallClockMillis() + 1);
        assertEquals(KvCodes.CODE_SUCCESS, kv.put(2, ba("parent.key1"), "value1".getBytes()).getBizCode());
        assertEquals(KvCodes.CODE_PARENT_NOT_DIR, kv.put(3, ba("parent.key1.key2"), "xxx".getBytes()).getBizCode());
        KvResult r = kv.get(ba("parent.key1"));
        assertEquals(KvCodes.CODE_SUCCESS, r.getBizCode());
        assertArrayEquals("value1".getBytes(), r.getNode().getData());
        r = kv.get(ba("parent"));
        assertEquals(KvCodes.CODE_SUCCESS, r.getBizCode());
        assertTrue(r.getNode().isDir());
        assertEquals(ts.getWallClockMillis() - 1, r.getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), r.getNode().getUpdateTime());
        assertEquals(1, r.getNode().getCreateIndex());
        assertEquals(2, r.getNode().getUpdateIndex());
        r = kv.get(ba(""));
        assertEquals(0, r.getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), r.getNode().getUpdateTime());
        assertEquals(0, r.getNode().getCreateIndex());
        assertEquals(2, r.getNode().getUpdateIndex());
    }

    @Test
    void testRemove() {
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.remove(1, ba("key1")).getBizCode());
        kv.put(2, ba("key1"), "value1".getBytes());
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(3, ba("key1")).getBizCode());
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("key1")).getBizCode());
    }

    @Test
    void testRemoveInDir() {
        kv.mkdir(1, ba("parent"));
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.remove(2, ba("parent.key1")).getBizCode());
        assertEquals(1, kv.get(ba("parent")).getNode().getUpdateIndex());
        assertEquals(1, kv.get(ba("")).getNode().getUpdateIndex());

        kv.put(3, ba("parent.key1"), "value1".getBytes());
        TestUtil.updateTimestamp(ts, ts.getNanoTime() + 1, ts.getWallClockMillis() + 1);
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(4, ba("parent.key1")).getBizCode());
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("parent.key1")).getBizCode());

        assertEquals(1, kv.get(ba("parent")).getNode().getCreateIndex());
        assertEquals(4, kv.get(ba("parent")).getNode().getUpdateIndex());
        assertEquals(ts.getWallClockMillis() - 1, kv.get(ba("parent")).getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), kv.get(ba("parent")).getNode().getUpdateTime());

        assertEquals(0, kv.get(ba("")).getNode().getCreateIndex());
        assertEquals(4, kv.get(ba("")).getNode().getUpdateIndex());
        assertEquals(0, kv.get(ba("")).getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), kv.get(ba("")).getNode().getUpdateTime());
    }

    @Test
    void testRemoveDir() {
        kv.mkdir(1, ba("parent"));
        kv.put(2, ba("parent.key1"), "value1".getBytes());
        kv.put(3, ba("parent.key2"), "value2".getBytes());
        assertEquals(KvCodes.CODE_HAS_CHILDREN, kv.remove(4, ba("parent")).getBizCode());
        kv.remove(5, ba("parent.key1"));
        assertEquals(KvCodes.CODE_HAS_CHILDREN, kv.remove(6, ba("parent")).getBizCode());
        kv.remove(7, ba("parent.key2"));
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(8, ba("parent")).getBizCode());
    }

    @Test
    void testList() {
        kv.put(1, ba("key1"), "a".getBytes());
        kv.put(2, ba("key2"), "b".getBytes());
        kv.mkdir(3, ba("dir1"));
        Pair<Integer, List<KvResult>> list = kv.list(ba(""));
        assertEquals(KvCodes.CODE_SUCCESS, list.getLeft());
        assertEquals(3, list.getRight().size());
        kv.remove(4, ba("key1"));
        list = kv.list(ba(""));
        assertEquals(2, list.getRight().size());
    }

    @Test
    void testListInDir() {
        kv.mkdir(1, ba("parent"));
        kv.put(2, ba("parent.key1"), "a".getBytes());
        kv.mkdir(3, ba("parent.dir1"));
        Pair<Integer, List<KvResult>> list = kv.list(ba("parent"));
        assertEquals(KvCodes.CODE_SUCCESS, list.getLeft());
        assertEquals(2, list.getRight().size());
    }

    @Test
    void testMkdir() {
        assertEquals(KvCodes.CODE_SUCCESS, kv.mkdir(1, ba("dir1")).getBizCode());
        TestUtil.updateTimestamp(ts, ts.getNanoTime() + 1, ts.getWallClockMillis() + 1);
        assertEquals(KvCodes.CODE_SUCCESS_OVERWRITE, kv.mkdir(2, ba("dir1")).getBizCode());
        KvResult r = kv.get(ba("dir1"));
        assertEquals(KvCodes.CODE_SUCCESS, r.getBizCode());
        assertTrue(r.getNode().isDir());
        assertEquals(ts.getWallClockMillis() - 1, r.getNode().getCreateTime());
        assertEquals(ts.getWallClockMillis(), r.getNode().getUpdateTime());
        assertEquals(1, r.getNode().getCreateIndex());
        assertEquals(2, r.getNode().getUpdateIndex());

        assertEquals(KvCodes.CODE_DIR_EXISTS, kv.put(3, ba("dir1"), "value1".getBytes()).getBizCode());
    }

    @Test
    void testInvalidKeyValue() {
        assertEquals(KvCodes.CODE_SUCCESS, kv.get(ba("")).getBizCode());
        assertTrue(kv.get(ba("")).getNode().isDir());
        assertEquals(KvCodes.CODE_SUCCESS, kv.get(null).getBizCode());
        assertTrue(kv.get(null).getNode().isDir());

        assertEquals(KvCodes.CODE_INVALID_KEY, kv.put(1, ba(""), "value1".getBytes()).getBizCode());
        assertEquals(KvCodes.CODE_INVALID_KEY, kv.put(1, null, "value1".getBytes()).getBizCode());

        assertEquals(KvCodes.CODE_INVALID_KEY, kv.remove(1, ba("")).getBizCode());
        assertEquals(KvCodes.CODE_INVALID_KEY, kv.remove(1, null).getBizCode());

        assertEquals(KvCodes.CODE_INVALID_VALUE, kv.put(1, ba("key1"), "".getBytes()).getBizCode());
        assertEquals(KvCodes.CODE_INVALID_VALUE, kv.put(1, ba("key1"), null).getBizCode());

        assertEquals(KvCodes.CODE_INVALID_KEY, kv.get(ba(".")).getBizCode());
        assertEquals(KvCodes.CODE_INVALID_KEY, kv.get(ba("a.b.")).getBizCode());
    }

    private KvSnapshot takeSnapshot() {
        long lastIndex = kv.root.latest.getUpdateIndex();
        SnapshotInfo si = new SnapshotInfo(lastIndex, 1, null, null,
                null, null, 0);
        return kv.takeSnapshot(si, () -> false, gcTask -> {
            //noinspection StatementWithEmptyBody
            while (gcTask.get()) ;
        });
    }

    @Test
    void testWithSnapshot() {
        int ver = 1;
        kv.mkdir(ver++, ba("parent"));
        kv.put(ver++, ba("key1"), "a".getBytes());
        kv.put(ver++, ba("key2"), "b".getBytes());
        kv.put(ver++, ba("parent.key1"), "c".getBytes());
        takeSnapshot();
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(ver++, ba("key1")).getBizCode());
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("key1")).getBizCode());
        assertEquals(KvCodes.CODE_SUCCESS_OVERWRITE, kv.put(ver++, ba("key2"), "b2".getBytes()).getBizCode());
        assertArrayEquals("b2".getBytes(), kv.get(ba("key2")).getNode().getData());
        takeSnapshot();
        assertEquals(KvCodes.CODE_SUCCESS_OVERWRITE, kv.put(ver++, ba("key2"), "b3".getBytes()).getBizCode());
        assertArrayEquals("b3".getBytes(), kv.get(ba("key2")).getNode().getData());
        takeSnapshot();
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(ver++, ba("key2")).getBizCode());
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(ver++, ba("parent.key1")).getBizCode());
        assertEquals(KvCodes.CODE_SUCCESS, kv.remove(ver++, ba("parent")).getBizCode());
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("key2")).getBizCode());
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("parent.key1")).getBizCode());
        assertEquals(KvCodes.CODE_NOT_FOUND, kv.get(ba("parent")).getBizCode());
        takeSnapshot();
        // change key2 to dir
        assertEquals(KvCodes.CODE_SUCCESS, kv.mkdir(ver++, ba("key2")).getBizCode());
        assertEquals(KvCodes.CODE_SUCCESS, kv.put(ver++, ba("key2.key1"), "d".getBytes()).getBizCode());
        assertEquals("d", new String(kv.get(ba("key2.key1")).getNode().getData()));
        //change parent to string
        assertEquals(KvCodes.CODE_SUCCESS, kv.put(ver++, ba("parent"), "e".getBytes()).getBizCode());
        assertEquals("e", new String(kv.get(ba("parent")).getNode().getData()));
    }

}


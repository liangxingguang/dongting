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

import com.github.dtprj.dongting.dtkv.KvNode;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.fiber.FiberGroup;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.sm.Snapshot;
import com.github.dtprj.dongting.raft.sm.SnapshotInfo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author huangli
 */
class KvSnapshot extends Snapshot {
    private final Supplier<Boolean> cancel;
    private final Consumer<Snapshot> closeCallback;
    private final ConcurrentHashMap<String, KvNodeHolder> map;
    private final long lastIncludeRaftIndex;

    private boolean processDir = true;
    private final LinkedList<KvNodeHolder> dirQueue = new LinkedList<>();
    private Iterator<Map.Entry<String, KvNodeHolder>> iterator;
    private KvNode currentKvNode;

    private final EncodeStatus encodeStatus = new EncodeStatus();

    public KvSnapshot(SnapshotInfo si, KvImpl kv, Supplier<Boolean> cancel, Consumer<Snapshot> closeCallback) {
        super(si);
        this.cancel = cancel;
        this.closeCallback = closeCallback;
        this.map = kv.map;
        this.dirQueue.addLast(kv.root);
        this.lastIncludeRaftIndex = si.getLastIncludedIndex();
    }

    @Override
    public FiberFuture<Integer> readNext(ByteBuffer buffer) {
        FiberGroup fiberGroup = FiberGroup.currentGroup();
        if (cancel.get()) {
            return FiberFuture.failedFuture(fiberGroup, new RaftException("canceled"));
        }

        int startPos = buffer.position();
        while (true) {
            if (currentKvNode == null) {
                loadNextNode();
            }
            if (currentKvNode == null) {
                if (processDir) {
                    processDir = false;
                    iterator = map.entrySet().iterator();
                    continue;
                } else {
                    // no more data
                    return FiberFuture.completedFuture(fiberGroup, buffer.position() - startPos);
                }
            }

            if (encodeStatus.writeToBuffer(buffer)) {
                encodeStatus.reset();
                currentKvNode = null;
            } else {
                // buffer is full
                return FiberFuture.completedFuture(fiberGroup, buffer.position() - startPos);
            }
        }
    }

    private void loadNextNode() {
        if (processDir) {
            if (iterator == null) {
                KvNodeHolder h = dirQueue.removeFirst();
                if (h == null) {
                    return;
                }
                iterator = h.latest.children.entrySet().iterator();
            }
        }
        while (iterator.hasNext()) {
            Map.Entry<String, KvNodeHolder> en = iterator.next();
            KvNodeHolder h = en.getValue();
            if (h == null) {
                return;
            }
            KvNodeEx n = h.latest;
            while (n != null && n.getCreateIndex() > lastIncludeRaftIndex) {
                n = n.previous;
            }
            if (n == null || n.removeAtIndex > 0) {
                continue;
            }
            if (processDir == n.isDir()) {
                String key = en.getKey();
                encodeStatus.keyBytes = key.getBytes(StandardCharsets.UTF_8);
                encodeStatus.valueBytes = n.getData();
                encodeStatus.createIndex = n.getCreateIndex();
                currentKvNode = n;
                return;
            }
        }
    }

    @Override
    protected void doClose() {
        closeCallback.accept(this);
    }
}

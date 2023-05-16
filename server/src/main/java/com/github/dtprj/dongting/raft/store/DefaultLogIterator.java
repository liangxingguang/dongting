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
package com.github.dtprj.dongting.raft.store;

import com.github.dtprj.dongting.buf.RefBufferFactory;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.impl.RaftExecutor;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * @author huangli
 */
class DefaultLogIterator implements RaftLog.LogIterator {
    private static final DtLog log = DtLogs.getLogger(DefaultLogIterator.class);

    private final IdxFileQueue idxFiles;
    private final LogFileQueue logFiles;
    private final RaftExecutor raftExecutor;
    private final RefBufferFactory heapPool;
    private final RaftGroupConfigEx groupConfig;
    private final ByteBuffer readBuffer;

    private final Supplier<Boolean> fullIndicator;
    private final CRC32C crc32c = new CRC32C();
    private final LogHeader header = new LogHeader();

    private long nextIndex = -1;
    private long nextPos = -1;

    // TODO check error handling
    private boolean error;
    private boolean close;

    private int bytes;
    private int limit;
    private int bytesLimit;
    private List<LogItem> result;
    private CompletableFuture<List<LogItem>> future;
    private LogItem item;

    DefaultLogIterator(IdxFileQueue idxFiles, LogFileQueue logFiles, RaftGroupConfigEx groupConfig, Supplier<Boolean> fullIndicator) {
        this.idxFiles = idxFiles;
        this.logFiles = logFiles;
        this.raftExecutor = (RaftExecutor) groupConfig.getRaftExecutor();
        this.readBuffer = groupConfig.getDirectPool().borrow(1024 * 1024);
        this.groupConfig = groupConfig;
        this.heapPool = groupConfig.getHeapPool();
        this.readBuffer.limit(0);
        this.fullIndicator = fullIndicator;
    }

    @Override
    public CompletableFuture<List<LogItem>> next(long index, int limit, int bytesLimit) {
        try {
            if (error) {
                BugLog.getLog().error("iterator has error");
                throw new RaftException("iterator has error");
            }
            if (nextIndex == -1) {
                nextPos = idxFiles.syncLoadLogPos(index);
                nextIndex = index;
            } else {
                if (nextIndex != index) {
                    throw new RaftException("nextIndex!=index");
                }
            }
            logFiles.checkPos(nextPos);

            this.result = new ArrayList<>();
            this.future = new CompletableFuture<>();
            this.item = null;
            this.bytes = 0;
            this.limit = limit;
            this.bytesLimit = bytesLimit;

            if (readBuffer.hasRemaining()) {
                extractAndLoadNextIfNecessary();
            } else {
                readBuffer.clear();
                loadLogFromStore();
            }
            return future;
        } catch (Throwable e) {
            error = true;
            return CompletableFuture.failedFuture(e);
        }
    }

    private void extractAndLoadNextIfNecessary() {
        int oldRemaining = readBuffer.remaining();

        boolean extractFinish = false;
        ByteBuffer buf = readBuffer;
        while (buf.remaining() >= LogHeader.ITEM_HEADER_SIZE) {
            if (item == null) {
                if (extractHeader(result, bytesLimit, buf)) {
                    extractFinish = true;
                }
            }
            if (extractItemBody()) {
                extractFinish = true;
            }
        }

        int extractBytes = oldRemaining - readBuffer.remaining();
        nextPos += extractBytes;
        if (extractFinish) {
            future.complete(result);
            nextIndex += result.size();
        } else {
            LogFileQueue.prepareNextRead(readBuffer);
            loadLogFromStore();
        }
    }

    private void loadLogFromStore() {
        long pos = nextPos;
        long rest = logFiles.restInCurrentFile(pos);
        if (rest <= 0) {
            error = true;
            log.error("rest is illegal. pos={}, writePos={}", pos, logFiles.getWritePos());
            future.completeExceptionally(new RaftException("rest is illegal."));
            return;
        }
        LogFile logFile = logFiles.getLogFile(pos);
        int fileStartPos = logFiles.filePos(pos);
        ByteBuffer readBuffer = this.readBuffer;
        if (rest < readBuffer.remaining()) {
            readBuffer.limit((int) (readBuffer.position() + rest));
        }
        AsyncIoTask t = new AsyncIoTask(readBuffer, fileStartPos, logFile, fullIndicator);
        logFile.use++;
        t.exec().whenCompleteAsync((v, ex) -> resumeAfterLoad(logFile, ex), raftExecutor);
    }

    private void resumeAfterLoad(LogFile logFile, Throwable ex) {
        try {
            logFile.use--;
            if (fullIndicator.get()) {
                error = true;
                future.cancel(false);
            } else if (ex != null) {
                error = true;
                future.completeExceptionally(ex);
            } else {
                readBuffer.flip();
                extractAndLoadNextIfNecessary();
            }
        } catch (Throwable e) {
            error = true;
            future.completeExceptionally(e);
        }
    }


    private boolean extractHeader(List<LogItem> result, int bytesLimit, ByteBuffer readBuffer) {
        LogHeader header = this.header;
        int startPos = readBuffer.position();
        header.read(crc32c, readBuffer);

        int bodyLen = header.totalLen - header.bizHeaderLen;
        if (!logFiles.checkHeader(nextPos, header)) {
            throw new RaftException("invalid log item length: totalLen=" + header.totalLen + ", nextPos=" + nextPos);
        }

        if (result.size() > 0 && bytes + bodyLen > bytesLimit) {
            // rollback position for next use
            readBuffer.position(startPos);
            return true;
        }

        LogItem li = new LogItem();
        this.item = li;
        li.setIndex(header.index);
        li.setType(header.type);
        li.setTerm(header.term);
        li.setPrevLogTerm(header.prevLogTerm);
        li.setTimestamp(header.timestamp);
        li.setDataSize(bodyLen);
        if (bodyLen > 0) {
            li.setBuffer(heapPool.create(bodyLen));
        }
        return false;
    }

    private boolean extractItemBody() {
        int bodyLen = header.bodyLen;
        if (bodyLen == 0) {
            result.add(item);
            item = null;
            return result.size() >= limit;
        } else {
            ByteBuffer destBuf = item.getBuffer().getBuffer();
            int read = destBuf.position();
            int restBytes = bodyLen - read;
            ByteBuffer buf = readBuffer;
            if (restBytes > 0 && buf.remaining() > 0) {
                LogFileQueue.updateCrc(crc32c, buf, buf.position(), restBytes);
            }
            if (buf.remaining() >= restBytes + 4) {
                buf.get(destBuf.array(), read, restBytes);
                destBuf.limit(bodyLen);
                destBuf.position(0);
                if (crc32c.getValue() != buf.getInt()) {
                    throw new RaftException("crc32c not match");
                }

                result.add(item);
                bytes += bodyLen;
                item = null;
                return result.size() >= limit;
            } else {
                int restBodyLen = Math.min(restBytes, buf.remaining());
                buf.get(destBuf.array(), read, restBodyLen);
                return false;
            }
        }
    }

    @Override
    public void close() {
        if (close) {
            BugLog.getLog().error("iterator has closed");
        } else {
            groupConfig.getDirectPool().release(readBuffer);
        }
        close = true;
    }
}

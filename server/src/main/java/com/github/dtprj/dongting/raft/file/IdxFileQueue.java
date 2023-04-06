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
package com.github.dtprj.dongting.raft.file;

import com.github.dtprj.dongting.common.BitUtil;
import com.github.dtprj.dongting.common.ObjUtil;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * @author huangli
 */
public class IdxFileQueue extends FileQueue implements IdxOps {
    private static final DtLog log = DtLogs.getLogger(IdxFileQueue.class);
    private static final int ITEM_LEN = 8;
    private static final int ITEMS_PER_FILE = 1024 * 1024;
    private static final int REMOVE_ITEMS = 512;
    private static final int MAX_CACHE_ITEMS = 16 * 1024;

    private static final int IDX_FILE_SIZE = ITEM_LEN * ITEMS_PER_FILE; //should divisible by FLUSH_ITEMS
    private static final int FILE_LEN_MASK = IDX_FILE_SIZE - 1;
    private static final int FILE_LEN_SHIFT_BITS = BitUtil.zeroCountOfBinary(IDX_FILE_SIZE);

    private static final int FLUSH_ITEMS = MAX_CACHE_ITEMS / 2;
    private static final int FLUSH_ITEMS_MAST = FLUSH_ITEMS - 1;
    private final LongLongSeqMap cache = new LongLongSeqMap();

    private long nextPersistIndex;
    private long nextIndex;
    private long firstIndex;

    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(FLUSH_ITEMS * ITEM_LEN);

    private WriteTask writeTask;

    public IdxFileQueue(File dir, Executor ioExecutor) {
        super(dir, ioExecutor);
    }

    @Override
    protected long getFileSize() {
        return IDX_FILE_SIZE;
    }

    public void initWithCommitIndex(long commitIndex) {
        this.nextPersistIndex = commitIndex + 1;
        this.nextIndex = commitIndex + 1;
        this.firstIndex = posToIndex(queueStartPosition);
    }

    @Override
    protected long getWritePos() {
        return indexToPos(nextIndex);
    }

    @Override
    public int getFileLenShiftBits() {
        return FILE_LEN_SHIFT_BITS;
    }

    private static long indexToPos(long index) {
        // each item 8 bytes
        return index << 3;
    }

    private static long posToIndex(long pos) {
        // each item 8 bytes
        return pos >>> 3;
    }

    public long findLogPosByItemIndex(long itemIndex) throws IOException {
        checkIndex(itemIndex);
        if (itemIndex < firstIndex) {
            return -1;
        }
        long result = cache.get(itemIndex);
        if (result > 0) {
            return result;
        }
        long pos = indexToPos(itemIndex);
        long filePos = pos & FILE_LEN_MASK;
        LogFile lf = getLogFile(pos);
        // TODO reuse it
        ByteBuffer buffer = ByteBuffer.allocate(8);
        FileUtil.syncRead(lf.channel, buffer, filePos);
        buffer.flip();
        return buffer.getLong();
    }

    public long truncateTail(long index) throws IOException {
        ObjUtil.checkPositive(index, "index");
        if (index < firstIndex) {
            throw new RaftException("truncateTail index is too small: " + index);
        }
        long value = findLogPosByItemIndex(index);
        cache.truncate(index);
        nextIndex = index;
        if (nextPersistIndex > index) {
            nextPersistIndex = index;
        }
        if (writeTask != null && writeTask.persistIndexAfterWrite > index) {
            writeTask.future.cancel(false);
            writeTask.persistIndexAfterWrite = 0;
        }
        return value;
    }

    private void checkIndex(long index) {
        ObjUtil.checkPositive(index, "index");
        if (index > nextIndex) {
            BugLog.getLog().error("index is too large : lastIndex={}, index={}", nextIndex, index);
            throw new RaftException("index is too large");
        }
    }

    @Override
    public void put(long itemIndex, long dataPosition) throws IOException {
        checkIndex(itemIndex);
        if (itemIndex < firstIndex) {
            BugLog.getLog().error("index is too small : firstIndex={}, index={}", firstIndex, itemIndex);
            throw new RaftException("index is too small");
        }
        LongLongSeqMap cache = this.cache;
        if (itemIndex < nextIndex) {
            cache.truncate(itemIndex);
        }
        if (cache.size() >= MAX_CACHE_ITEMS) {
            cache.remove(REMOVE_ITEMS);
        }
        cache.put(itemIndex, dataPosition);
        nextIndex = itemIndex + 1;
        if ((nextIndex & FLUSH_ITEMS_MAST) == 0) {
            writeAndFlush();
        }
    }

    private void writeAndFlush() throws IOException {
        if (!ensureWritePosReady()) {
            return;
        }
        if (!ensureLastWriteFinish()) {
            return;
        }
        ByteBuffer writeBuffer = this.writeBuffer;
        LongLongSeqMap cache = this.cache;
        writeBuffer.clear();
        long index = nextPersistIndex;
        long startPos = indexToPos(index);
        long lastKey = cache.getLastKey();
        for (int i = 0; i < FLUSH_ITEMS && index <= lastKey; i++, index++) {
            if ((index & FLUSH_ITEMS_MAST) == 0 && i != 0) {
                // don't pass end of file or sections
                break;
            }
            long value = cache.get(index);
            writeBuffer.putLong(value);
        }
        writeBuffer.flip();

        writeTask = new WriteTask();
        WriteTask writeTask = this.writeTask;
        writeTask.logFile = getLogFile(startPos);
        writeTask.writeStartPosOfFile = startPos & FILE_LEN_MASK;
        writeTask.currentWritePosOfFile = writeTask.writeStartPosOfFile;
        writeTask.persistIndexAfterWrite = index;
        writeTask.future = new CompletableFuture<>();
        writeTask.writeBuffer = writeBuffer;
        writeBuffer.mark();
        writeTask.doWrite();
    }

    private static class WriteTask implements CompletionHandler<Integer, Void> {
        LogFile logFile;
        long writeStartPosOfFile;
        long currentWritePosOfFile;
        CompletableFuture<Void> future;
        long persistIndexAfterWrite;
        ByteBuffer writeBuffer;

        private void doWrite() {
            logFile.channel.write(writeBuffer, currentWritePosOfFile, null, this);
        }

        private void retryWrite() {
            writeBuffer.reset();
            currentWritePosOfFile = writeStartPosOfFile;
            future = new CompletableFuture<>();
            doWrite();
        }

        @Override
        public void completed(Integer result, Void attachment) {
            if (future.isCancelled()) {
                return;
            }
            if (writeBuffer.hasRemaining()) {
                currentWritePosOfFile += result;
                doWrite();
            } else {
                try {
                    logFile.channel.force(false);
                    future.complete(null);
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            }
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            future.completeExceptionally(exc);
        }
    }

    private boolean ensureLastWriteFinish() {
        WriteTask writeTask = this.writeTask;
        if (writeTask == null) {
            return true;
        }
        while(true) {
            try {
                writeTask.future.get();
                if (writeTask.persistIndexAfterWrite > 0) {
                    nextPersistIndex = writeTask.persistIndexAfterWrite;
                }
                return true;
            } catch (CancellationException e) {
                log.info("previous write canceled");
                return true;
            } catch (InterruptedException e) {
                log.info("write index interrupted: {}", writeTask.logFile.pathname);
                return false;
            } catch (ExecutionException e) {
                log.error("write idx file failed: {}", writeTask.logFile.pathname, e);
                if (e.getCause() instanceof IOException) {
                    writeTask.retryWrite();
                }
                throw new RaftException(e);
            } finally {
                this.writeTask = null;
            }
        }
    }

    public long getNextIndex() {
        return nextIndex;
    }
}

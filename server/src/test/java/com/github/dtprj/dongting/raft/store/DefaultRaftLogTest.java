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

import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.fiber.BaseFiberTest;
import com.github.dtprj.dongting.fiber.Fiber;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.fiber.FrameCallResult;
import com.github.dtprj.dongting.raft.impl.InitFiberFrame;
import com.github.dtprj.dongting.raft.impl.RaftStatusImpl;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;
import com.github.dtprj.dongting.raft.test.MockExecutors;
import com.github.dtprj.dongting.raft.test.TestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author huangli
 */
public class DefaultRaftLogTest extends BaseFiberTest {
    private String dataDir;
    private RaftStatusImpl raftStatus;
    private RaftGroupConfigEx config;
    private StatusManager statusManager;
    private DefaultRaftLog raftLog;

    @BeforeEach
    void setup() throws Exception {
        dataDir = TestDir.testDir(DefaultRaftLogTest.class.getSimpleName());
        init();
    }

    private void init() throws Exception {
        raftStatus = new RaftStatusImpl(dispatcher.getTs());
        RaftServerConfig serverConfig = new RaftServerConfig();
        config = new RaftGroupConfigEx(1, "1", "1");
        config.setFiberGroup(fiberGroup);
        config.setDataDir(dataDir);
        config.setBlockIoExecutor(MockExecutors.ioExecutor());
        config.setTs(raftStatus.getTs());

        config.setRaftStatus(raftStatus);
        statusManager = new StatusManager(config);
        doInFiber(new FiberFrame<>() {
            @Override
            public FrameCallResult execute(Void input) {
                return Fiber.call(statusManager.initStatusFile(), this::resume);
            }

            private FrameCallResult resume(Void v) {
                return Fiber.frameReturn();
            }
        });

        raftLog = new DefaultRaftLog(config, statusManager, null, 1);
        raftLog.idxItemsPerFile = 8;
        config.setIdxCacheSize(4);
        config.setIdxFlushThreshold(2);
        raftLog.logFileSize = 1024;
        doInFiber(new FiberFrame<>() {
            @Override
            public FrameCallResult execute(Void input) {
                InitFiberFrame.initRaftStatus(raftStatus, fiberGroup, serverConfig);
                return Fiber.call(raftLog.init(), this::resume);
            }

            private FrameCallResult resume(Pair<Integer, Long> integerLongPair) {
                return Fiber.frameReturn();
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        doInFiber(new FiberFrame<>() {
            @Override
            public FrameCallResult execute(Void input) {
                FiberFuture<Void> f1 = raftLog.close();
                FiberFuture<Void> f2 = statusManager.close();
                return FiberFuture.allOf("close", f1, f2).await(this::justReturn);
            }
        });
    }

    private void append(long index, int[] totalSizes, int[] bizHeaderLen) throws Exception {
        ArrayList<LogItem> list = new ArrayList<>();
        for (int i = 0; i < totalSizes.length; i++) {
            LogItem li = LogFileQueueTest.createItem(config, 100, 100, index++, totalSizes[i], bizHeaderLen[i]);
            list.add(li);
        }

        long lastIdx = index - 1;
        doInFiber(new FiberFrame<>() {
            @Override
            public FrameCallResult execute(Void input) {
                raftLog.append(list);
                return waitWriteFinish(null);
            }

            private FrameCallResult waitWriteFinish(Void v) {
                if (raftStatus.getLastForceLogIndex() < lastIdx) {
                    return raftStatus.getLogForceFinishCondition().await(1000, this::waitWriteFinish);
                } else {
                    return Fiber.frameReturn();
                }
            }
        });
    }

    @Test
    void testInit() throws Exception {
        int[] totalSizes = new int[]{400, 400, 512};
        int[] bizHeaderLen = new int[]{1, 0, 400};
        append(1, totalSizes, bizHeaderLen);
        tearDown();
        init();
    }

    @Test
    void testDelete() throws Exception {
        int[] totalSizes = new int[]{400, 400, 512, 200, 400};
        int[] bizHeaderLen = new int[]{1, 0, 400, 100, 1};
        append(1, totalSizes, bizHeaderLen);
        raftStatus.setCommitIndex(5);
        raftStatus.setLastApplied(5);
        raftStatus.setLastLogIndex(5);
        append(6, totalSizes, bizHeaderLen);

        doInFiber(new FiberFrame<>() {
            @Override
            public FrameCallResult execute(Void input) {
                // to fire idx flush
                return raftLog.close().await(this::resume);
            }

            private FrameCallResult resume(Void unused) {
                return statusManager.close().await(this::justReturn);
            }
        });

        init();
        raftStatus.setCommitIndex(5);
        raftStatus.setLastApplied(5);
        raftStatus.setLastLogIndex(5);
        raftStatus.setLastForceLogIndex(5);

        // test delete
        File dir = new File(new File(dataDir), "log");

        {
            Supplier<Boolean> deleted = fileDeleted(dir, 0);
            doInFiber(() -> raftLog.markTruncateByIndex(3, 1000));
            Thread.sleep(2);
            assertFalse(deleted.get());
            plus1Hour();
            TestUtil.waitUtil(deleted);
        }
        {
            plus1Hour();
            doInFiber(() -> raftLog.markTruncateByTimestamp(raftStatus.getTs().getWallClockMillis(), 0));

            // can't delete after next persist index and apply index, so only delete to index 4
            Supplier<Boolean> deleted = fileDeleted(dir, 1024);
            plus1Hour();
            TestUtil.waitUtil(deleted);
        }
    }

    private static Supplier<Boolean> fileDeleted(File dir, long startPos) {
        return () -> {
            String[] names = dir.list();
            String firstFileName = String.format("%020d", startPos);
            if (names != null) {
                for (String n : names) {
                    if (n.equals(firstFileName)) {
                        return false;
                    }
                }
            }
            return true;
        };
    }

    private void plus1Hour() throws Exception {
        doInFiber(() -> TestUtil.plus1Hour(raftStatus.getTs()));
    }

    @Test
    void testTruncate() throws Exception {
        int[] totalSizes = new int[]{800, 512, 256, 256, 512};
        int[] bizHeaderLen = new int[]{10, 10, 10, 10, 10};
        append(1, totalSizes, bizHeaderLen);
        raftStatus.setCommitIndex(1);
        doInFiber(new FiberFrame<>() {
            @Override
            public FrameCallResult execute(Void input) {
                LogFile logFile = raftLog.logFiles.getLogFile(2048);
                assertEquals(5, logFile.firstIndex);
                return Fiber.call(raftLog.truncateTail(5), this::resume1);
            }

            private FrameCallResult resume1(Void unused) {
                LogFile logFile = raftLog.logFiles.getLogFile(2048);
                assertEquals(0, logFile.firstIndex);
                logFile = raftLog.logFiles.getLogFile(1024);
                assertEquals(2, logFile.firstIndex);
                assertEquals(5, raftLog.logFiles.logAppender.nextPersistIndex);
                assertEquals(2048, raftLog.logFiles.logAppender.nextPersistPos);
                return Fiber.call(raftLog.truncateTail(3), this::resume2);
            }

            private FrameCallResult resume2(Void unused) {
                LogFile logFile = raftLog.logFiles.getLogFile(1024);
                assertEquals(2, logFile.firstIndex);
                assertEquals(3, raftLog.logFiles.logAppender.nextPersistIndex);
                assertEquals(1536, raftLog.logFiles.logAppender.nextPersistPos);
                return Fiber.call(raftLog.truncateTail(2), this::resume3);
            }

            private FrameCallResult resume3(Void unused) {
                LogFile logFile = raftLog.logFiles.getLogFile(1024);
                assertEquals(0, logFile.firstIndex);
                assertEquals(2, raftLog.logFiles.logAppender.nextPersistIndex);
                assertEquals(800, raftLog.logFiles.logAppender.nextPersistPos);
                return Fiber.frameReturn();
            }
        });
    }
}

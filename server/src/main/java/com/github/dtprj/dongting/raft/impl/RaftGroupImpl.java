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
package com.github.dtprj.dongting.raft.impl;

import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.FlowControlException;
import com.github.dtprj.dongting.common.FutureCallback;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.fiber.FiberGroup;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.NotLeaderException;
import com.github.dtprj.dongting.raft.server.RaftCallback;
import com.github.dtprj.dongting.raft.server.RaftGroup;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftInput;
import com.github.dtprj.dongting.raft.sm.StateMachine;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public final class RaftGroupImpl extends RaftGroup {
    private static final DtLog log = DtLogs.getLogger(RaftGroupImpl.class);
    private final int groupId;
    private final RaftStatusImpl raftStatus;
    private final RaftGroupConfigEx groupConfig;
    private final PendingStat serverStat;
    private final StateMachine stateMachine;
    private final FiberGroup fiberGroup;

    private final Timestamp readTimestamp = new Timestamp();
    private final GroupComponents gc;
    private CompletableFuture<Void> shutdownFuture;

    public RaftGroupImpl(GroupComponents gc) {
        this.gc = gc;
        this.groupId = gc.groupConfig.groupId;

        this.raftStatus = gc.raftStatus;
        this.groupConfig = gc.groupConfig;
        this.serverStat = gc.serverStat;
        this.stateMachine = gc.stateMachine;
        this.fiberGroup = gc.fiberGroup;
    }

    @Override
    public int getGroupId() {
        return groupId;
    }

    @Override
    public boolean isLeader() {
        RaftMember leader = raftStatus.getShareStatus().currentLeader;
        return leader != null && leader.getNode().isSelf();
    }

    @Override
    public StateMachine getStateMachine() {
        return stateMachine;
    }

    @Override
    public void submitLinearTask(RaftInput input, RaftCallback callback) {
        Objects.requireNonNull(input);
        if (fiberGroup.isShouldStop()) {
            RaftUtil.release(input);
            throw new RaftException("raft group thread is stop");
        }
        int currentPendingWrites = (int) PendingStat.PENDING_REQUESTS.getAndAddRelease(serverStat, 1);
        if (currentPendingWrites >= groupConfig.maxPendingRaftTasks) {
            RaftUtil.release(input);
            String msg = "submitRaftTask failed: too many pending writes, currentPendingWrites=" + currentPendingWrites;
            log.warn(msg);
            PendingStat.PENDING_REQUESTS.getAndAddRelease(serverStat, -1);
            throw new FlowControlException(msg);
        }
        long size = input.getFlowControlSize();
        long currentPendingWriteBytes = (long) PendingStat.PENDING_BYTES.getAndAddRelease(serverStat, size);
        if (currentPendingWriteBytes >= groupConfig.maxPendingTaskBytes) {
            RaftUtil.release(input);
            String msg = "too many pending write bytes,currentPendingWriteBytes="
                    + currentPendingWriteBytes + ", currentRequestBytes=" + size;
            log.warn(msg);
            PendingStat.PENDING_BYTES.getAndAddRelease(serverStat, -size);
            throw new FlowControlException(msg);
        }
        RaftCallback wrapper = new RaftCallback() {
            @Override
            public void success(long raftIndex, Object result) {
                PendingStat.PENDING_REQUESTS.getAndAddRelease(serverStat, -1);
                PendingStat.PENDING_BYTES.getAndAddRelease(serverStat, -size);
                RaftCallback.callSuccess(callback, raftIndex, result);
            }

            @Override
            public void fail(Throwable ex) {
                PendingStat.PENDING_REQUESTS.getAndAddRelease(serverStat, -1);
                PendingStat.PENDING_BYTES.getAndAddRelease(serverStat, -size);
                RaftCallback.callFail(callback, ex);
            }
        };
        int type = input.isReadOnly() ? LogItem.TYPE_LOG_READ : LogItem.TYPE_NORMAL;
        gc.linearTaskRunner.submitRaftTaskInBizThread(type, input, wrapper);
    }

    @Override
    public void leaseRead(DtTime deadline, FutureCallback<Long> callback) {
        if (fiberGroup.isShouldStop()) {
            FutureCallback.callFail(callback, new RaftException("raft group thread is stop"));
            return;
        }
        ShareStatus ss = raftStatus.getShareStatus();
        if (ss.role != RaftRole.leader) {
            FutureCallback.callFail(callback, new NotLeaderException(
                    ss.currentLeader == null ? null : ss.currentLeader.getNode()));
            return;
        }

        // NOTICE : timestamp is not thread safe
        if (ss.groupReady) {
            readTimestamp.refresh(1);
            long t = readTimestamp.getNanoTime();
            if (ss.leaseEndNanos - t < 0) {
                FutureCallback.callFail(callback, new NotLeaderException(null));
            } else {
                FutureCallback.callSuccess(callback, ss.lastApplied);
            }
        } else {
            // wait group ready
            CompletableFuture<Long> f = gc.applyManager.addToWaitReadyQueue(deadline);
            f.whenComplete((idx, ex) -> {
                if (ex != null) {
                    FutureCallback.callFail(callback, ex);
                } else {
                    FutureCallback.callSuccess(callback, idx);
                }
            });
        }
    }

    @Override
    public void markTruncateByIndex(long index, long delayMillis) {
        ExecutorService executor = gc.fiberGroup.getExecutor();
        executor.execute(() -> gc.raftLog.markTruncateByIndex(index, delayMillis));
    }

    @Override
    public void markTruncateByTimestamp(long timestampMillis, long delayMillis) {
        ExecutorService executor = gc.fiberGroup.getExecutor();
        executor.execute(() -> gc.raftLog.markTruncateByTimestamp(timestampMillis, delayMillis));
    }

    @Override
    public CompletableFuture<Long> fireSaveSnapshot() {
        checkStatus();
        CompletableFuture<Long> f = new CompletableFuture<>();
        gc.fiberGroup.getExecutor().execute(() -> {
            try {
                FiberFuture<Long> ff = gc.snapshotManager.saveSnapshot();
                ff.registerCallback((idx, ex) -> {
                    if (ex != null) {
                        f.completeExceptionally(ex);
                    } else {
                        f.complete(idx);
                    }
                });
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    @Override
    public CompletableFuture<Void> transferLeadership(int nodeId, long timeoutMillis) {
        checkStatus();
        CompletableFuture<Void> f = new CompletableFuture<>();
        DtTime deadline = new DtTime(timeoutMillis, TimeUnit.MILLISECONDS);
        gc.memberManager.transferLeadership(nodeId, f, deadline);
        return f;
    }

    private void checkStatus() {
        CompletableFuture<Void> f = gc.memberManager.getPingReadyFuture();
        if (!f.isDone() || f.isCompletedExceptionally()) {
            throw new RaftException("not initialized");
        }
        if (raftStatus.fiberGroup.isShouldStop()) {
            throw new RaftException("raft group is not running");
        }
    }

    @Override
    public CompletableFuture<Long> leaderPrepareJointConsensus(Set<Integer> members, Set<Integer> observers,
                                                               Set<Integer> preparedMembers, Set<Integer> prepareObservers) {
        if (preparedMembers.isEmpty()) {
            throw new RaftException("preparedMembers are empty");
        }
        checkStatus();
        for (int nodeId : preparedMembers) {
            if (prepareObservers.contains(nodeId)) {
                log.error("node is both member and observer: nodeId={}, groupId={}", nodeId, groupId);
                throw new RaftException("node is both member and observer: " + nodeId);
            }
        }
        CompletableFuture<Long> f = new CompletableFuture<>();
        FiberFrame<Void> ff = gc.memberManager.leaderPrepareJointConsensus(members, observers, preparedMembers, prepareObservers, f);
        gc.fiberGroup.fireFiber("leaderPrepareJointConsensus", ff);
        return f;
    }

    @Override
    public CompletableFuture<Long> leaderAbortJointConsensus() {
        checkStatus();
        CompletableFuture<Long> f = new CompletableFuture<>();
        FiberFrame<Void> ff = gc.memberManager.leaderAbortJointConsensus(f);
        gc.fiberGroup.fireFiber("leaderAbortJointConsensus", ff);
        return f;
    }

    @Override
    public CompletableFuture<Long> leaderCommitJointConsensus(long prepareIndex) {
        checkStatus();
        CompletableFuture<Long> f = new CompletableFuture<>();
        FiberFrame<Void> ff = gc.memberManager.leaderCommitJointConsensus(f, prepareIndex);
        gc.fiberGroup.fireFiber("leaderCommitJointConsensus", ff);
        return f;
    }

    public GroupComponents getGroupComponents() {
        return gc;
    }

    public FiberGroup getFiberGroup() {
        return fiberGroup;
    }

    public CompletableFuture<Void> getShutdownFuture() {
        return shutdownFuture;
    }

    public void setShutdownFuture(CompletableFuture<Void> shutdownFuture) {
        this.shutdownFuture = shutdownFuture;
    }
}

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

import com.github.dtprj.dongting.common.IndexedQueue;
import com.github.dtprj.dongting.fiber.Fiber;
import com.github.dtprj.dongting.fiber.FiberCondition;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FiberGroup;
import com.github.dtprj.dongting.fiber.FrameCallResult;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.raft.RaftException;

import java.util.List;

/**
 * @author huangli
 */
public class CommitManager {

    private final GroupComponents gc;
    private final RaftStatusImpl raftStatus;
    private ApplyManager applyManager;
    private final IndexedQueue<AppendRespWriter> respQueue = new IndexedQueue<>(128);
    private final boolean syncForce;

    public CommitManager(GroupComponents gc) {
        this.gc = gc;
        this.raftStatus = gc.raftStatus;
        this.syncForce = gc.groupConfig.syncForce;
    }

    public void postInit() {
        this.applyManager = gc.applyManager;
    }

    public void startCommitFiber() {
        Fiber fiber = new Fiber("commit" + raftStatus.groupId, FiberGroup.currentGroup(),
                new CommitFiberFrame(), true, 50);
        fiber.start();
    }

    private class CommitFiberFrame extends FiberFrame<Void> {

        @Override
        protected FrameCallResult handle(Throwable ex) {
            BugLog.getLog().error("commit fiber error", ex);
            if (!isGroupShouldStopPlain()) {
                startCommitFiber();
            }
            return Fiber.frameReturn();
        }

        @Override
        public FrameCallResult execute(Void input) {
            RaftStatusImpl raftStatus = CommitManager.this.raftStatus;
            long idx = syncForce ? raftStatus.lastForceLogIndex : raftStatus.lastWriteLogIndex;
            if (idx > raftStatus.commitIndex) {
                CommitManager.this.logWriteFinish(idx);
            }
            FiberCondition c = syncForce ? raftStatus.logForceFinishCondition
                    : raftStatus.logWriteFinishCondition;
            return c.await(1000, this);
        }
    }

    private void logWriteFinish(long lastPersistIndex) {
        RaftStatusImpl raftStatus = this.raftStatus;
        if (lastPersistIndex > raftStatus.lastLogIndex) {
            throw Fiber.fatal(new RaftException("lastPersistIndex > lastLogIndex. lastPersistIndex="
                    + lastPersistIndex + ", lastLogIndex=" + raftStatus.lastLogIndex));
        }
        if (raftStatus.getRole() == RaftRole.leader) {
            RaftMember self = raftStatus.self;
            self.setNextIndex(lastPersistIndex + 1);
            self.setMatchIndex(lastPersistIndex);
            self.setLastConfirmReqNanos(raftStatus.ts.getNanoTime());

            RaftUtil.updateLease(raftStatus);
            // not call raftStatus.copyShareStatus(), invoke after apply

            leaderTryCommit(lastPersistIndex);
        } else {
            while (respQueue.size() > 0) {
                AppendRespWriter writer = respQueue.get(0);
                if (writer.writeResp(lastPersistIndex)) {
                    respQueue.removeFirst();
                } else {
                    break;
                }
            }
            followerTryCommit(raftStatus);
        }
    }

    public void followerTryCommit(RaftStatusImpl raftStatus) {
        long lastPersistIndex = syncForce ? raftStatus.lastForceLogIndex : raftStatus.lastWriteLogIndex;
        long leaderCommit = raftStatus.leaderCommit;
        long oldCommitIndex = raftStatus.commitIndex;
        if (leaderCommit > oldCommitIndex) {
            long newCommitIndex = Math.min(lastPersistIndex, leaderCommit);
            if (newCommitIndex > oldCommitIndex) {
                raftStatus.commitIndex = newCommitIndex;
                applyManager.wakeupApply();
            }
        }
    }

    public void leaderTryCommit(long recentMatchIndex) {
        RaftStatusImpl raftStatus = this.raftStatus;

        if (!needCommit(recentMatchIndex, raftStatus)) {
            return;
        }
        // leader can only commit log in current term, see raft paper 5.4.2
        if (recentMatchIndex < raftStatus.groupReadyIndex) {
            return;
        }
        raftStatus.commitIndex = recentMatchIndex;
        applyManager.wakeupApply();
    }

    private static boolean needCommit(long recentMatchIndex, RaftStatusImpl raftStatus) {
        boolean needCommit = needCommit(raftStatus.commitIndex, recentMatchIndex,
                raftStatus.members, raftStatus.rwQuorum);
        if (needCommit && !raftStatus.preparedMembers.isEmpty()) {
            int prepareRwQuorum = RaftUtil.getRwQuorum(raftStatus.preparedMembers.size());
            needCommit = needCommit(raftStatus.commitIndex, recentMatchIndex,
                    raftStatus.preparedMembers, prepareRwQuorum);
        }
        return needCommit;
    }


    @SuppressWarnings("ForLoopReplaceableByForEach")
    private static boolean needCommit(long currentCommitIndex, long recentMatchIndex,
                                      List<RaftMember> servers, int rwQuorum) {
        if (recentMatchIndex < currentCommitIndex) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < servers.size(); i++) {
            RaftMember member = servers.get(i);
            if (member.getNode().self) {
                if (recentMatchIndex > member.getMatchIndex()) {
                    return false;
                }
            }
            if (member.getMatchIndex() >= recentMatchIndex) {
                count++;
            }
        }
        return count >= rwQuorum;
    }

    public void registerRespWriter(AppendRespWriter writer) {
        respQueue.addLast(writer);
    }

    public interface AppendRespWriter {
        boolean writeResp(long lastPersistIndex);
    }

}

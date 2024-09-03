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
package com.github.dtprj.dongting.raft.rpc;

import com.github.dtprj.dongting.codec.DecodeContext;
import com.github.dtprj.dongting.codec.DecoderCallback;
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.fiber.Fiber;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.fiber.FrameCallResult;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.CmdCodes;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.ReadPacket;
import com.github.dtprj.dongting.net.WritePacket;
import com.github.dtprj.dongting.raft.impl.GroupComponents;
import com.github.dtprj.dongting.raft.impl.LinearTaskRunner;
import com.github.dtprj.dongting.raft.impl.MemberManager;
import com.github.dtprj.dongting.raft.impl.RaftRole;
import com.github.dtprj.dongting.raft.impl.RaftStatusImpl;
import com.github.dtprj.dongting.raft.impl.RaftTask;
import com.github.dtprj.dongting.raft.impl.RaftUtil;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroup;
import com.github.dtprj.dongting.raft.server.RaftInput;
import com.github.dtprj.dongting.raft.server.RaftServer;
import com.github.dtprj.dongting.raft.server.ReqInfo;
import com.github.dtprj.dongting.raft.sm.RaftCodecFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author huangli
 */
public class AppendProcessor extends RaftSequenceProcessor<Object> {

    public static final int APPEND_SUCCESS = 0;
    public static final int APPEND_LOG_NOT_MATCH = 1;
    public static final int APPEND_PREV_LOG_INDEX_LESS_THAN_LOCAL_COMMIT = 2;
    public static final int APPEND_REQ_ERROR = 3;
    public static final int APPEND_INSTALL_SNAPSHOT = 4;
    public static final int APPEND_NOT_MEMBER_IN_GROUP = 5;
    public static final int APPEND_SERVER_ERROR = 6;

    private final Function<Integer, RaftCodecFactory> decoderFactory;

    public AppendProcessor(RaftServer raftServer) {
        super(raftServer);
        this.decoderFactory = groupId -> {
            RaftGroup g = raftServer.getRaftGroup(groupId);
            return g == null ? null : g.getStateMachine();
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected int getGroupId(ReadPacket frame) {
        if (frame.getCommand() == Commands.RAFT_APPEND_ENTRIES) {
            ReadPacket<AppendReqCallback> f = (ReadPacket<AppendReqCallback>) frame;
            return f.getBody().getGroupId();
        } else {
            ReadPacket<InstallSnapshotReq> f = (ReadPacket<InstallSnapshotReq>) frame;
            return f.getBody().groupId;
        }
    }

    @Override
    protected void cleanReqInProcessorThread(ReqInfo<Object> reqInfo) {
        // if no error occurs in io thread, this method will not be called.
        // AppendProcessor do not call invokeCleanUp()
        ReadPacket<Object> f = reqInfo.getReqFrame();
        if (f.getCommand() == Commands.RAFT_APPEND_ENTRIES) {
            AppendReqCallback req = (AppendReqCallback) f.getBody();
            RaftUtil.release(req.getLogs());
        } else {
            InstallSnapshotReq req = (InstallSnapshotReq) f.getBody();
            req.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public DecoderCallback createDecoderCallback(int command, DecodeContext context) {
        if (command == Commands.RAFT_APPEND_ENTRIES) {
            return context.toDecoderCallback(new AppendReqCallback(decoderFactory));
        } else {
            return context.toDecoderCallback(new InstallSnapshotReq.Callback());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected FiberFrame<Void> processInFiberGroup(ReqInfoEx reqInfo) {
        if (reqInfo.getReqFrame().getCommand() == Commands.RAFT_APPEND_ENTRIES) {
            return new AppendFiberFrame(reqInfo, this);
        } else {
            return new InstallFiberFrame(reqInfo, this);
        }
    }

    public static String getAppendResultStr(int code) {
        switch (code) {
            case APPEND_SUCCESS:
                return "CODE_SUCCESS";
            case APPEND_LOG_NOT_MATCH:
                return "CODE_LOG_NOT_MATCH";
            case APPEND_PREV_LOG_INDEX_LESS_THAN_LOCAL_COMMIT:
                return "CODE_PREV_LOG_INDEX_LESS_THAN_LOCAL_COMMIT";
            case APPEND_REQ_ERROR:
                return "CODE_REQ_ERROR";
            case APPEND_INSTALL_SNAPSHOT:
                return "CODE_INSTALL_SNAPSHOT";
            case APPEND_NOT_MEMBER_IN_GROUP:
                return "CODE_NOT_MEMBER_IN_GROUP";
            case APPEND_SERVER_ERROR:
                return "CODE_SERVER_ERROR";
            default:
                return "CODE_UNKNOWN_" + code;
        }
    }

    @Override
    public void writeResp(ReqInfo<?> reqInfo, WritePacket respFrame) {
        super.writeResp(reqInfo, respFrame);
    }
}

abstract class AbstractAppendFrame<C> extends FiberFrame<Void> {
    private static final DtLog log = DtLogs.getLogger(AbstractAppendFrame.class);

    private final String appendType;
    protected final GroupComponents gc;
    protected final AppendProcessor processor;
    protected final ReqInfoEx<C> reqInfo;

    public AbstractAppendFrame(String appendType, AppendProcessor processor, ReqInfoEx<C> reqInfo) {
        this.appendType = appendType;
        this.gc = reqInfo.getRaftGroup().getGroupComponents();
        this.processor = processor;
        this.reqInfo = reqInfo;
    }

    protected abstract int getLeaderId();

    protected abstract int getRemoteTerm();

    protected abstract FrameCallResult process() throws Exception;

    @Override
    public FrameCallResult execute(Void input) throws Throwable {
        int remoteTerm = getRemoteTerm();
        int leaderId = getLeaderId();
        RaftStatusImpl raftStatus = gc.getRaftStatus();
        if (gc.getMemberManager().checkLeader(leaderId)) {
            int localTerm = raftStatus.getCurrentTerm();
            if (remoteTerm == localTerm) {
                if (raftStatus.getRole() == RaftRole.follower) {
                    gc.getVoteManager().cancelVote();
                    RaftUtil.resetElectTimer(raftStatus);
                    RaftUtil.updateLeader(raftStatus, leaderId);
                    return process();
                } else if (raftStatus.getRole() == RaftRole.observer) {
                    gc.getVoteManager().cancelVote();
                    RaftUtil.updateLeader(raftStatus, leaderId);
                    return process();
                } else if (raftStatus.getRole() == RaftRole.candidate) {
                    gc.getVoteManager().cancelVote();
                    RaftUtil.changeToFollower(raftStatus, leaderId);
                    return process();
                } else {
                    BugLog.getLog().error("leader receive {} request. term={}, remote={}", appendType,
                            remoteTerm, reqInfo.getReqContext().getDtChannel().getRemoteAddr());
                    return writeAppendResp(AppendProcessor.APPEND_REQ_ERROR, "leader receive raft install snapshot request");
                }
            } else if (remoteTerm > localTerm) {
                gc.getVoteManager().cancelVote();
                RaftUtil.incrTerm(remoteTerm, raftStatus, leaderId);
                gc.getStatusManager().persistAsync(true);
                return gc.getStatusManager().waitUpdateFinish(this);
            } else {
                log.info("receive {} request with a smaller term, ignore, remoteTerm={}, localTerm={}",
                        appendType, remoteTerm, localTerm);
                return writeAppendResp(AppendProcessor.APPEND_REQ_ERROR, "small term");
            }
        } else {
            log.warn("receive {} request from a non-member, ignore. remoteId={}, group={}, remote={}", appendType,
                    leaderId, reqInfo.getRaftGroup().getGroupId(), reqInfo.getReqContext().getDtChannel().getRemoteAddr());
            return writeAppendResp(AppendProcessor.APPEND_NOT_MEMBER_IN_GROUP, "not member");
        }
    }

    protected FrameCallResult writeAppendResp(int code, int suggestTerm, long suggestIndex, String msg) {
        AppendRespWritePacket resp = new AppendRespWritePacket();
        resp.setTerm(gc.getRaftStatus().getCurrentTerm());
        if (code == AppendProcessor.APPEND_SUCCESS) {
            resp.setSuccess(true);
        } else {
            resp.setSuccess(false);
            resp.setAppendCode(code);
        }
        resp.setRespCode(CmdCodes.SUCCESS);
        resp.setSuggestTerm(suggestTerm);
        resp.setSuggestIndex(suggestIndex);
        resp.setMsg(msg);
        processor.writeResp(reqInfo, resp);
        return Fiber.frameReturn();
    }

    protected FrameCallResult writeAppendResp(int code, String msg) {
        return writeAppendResp(code, 0, 0, msg);
    }
}

class AppendFiberFrame extends AbstractAppendFrame<AppendReqCallback> {

    private static final DtLog log = DtLogs.getLogger(AppendProcessor.class);

    private boolean needRelease = true;

    public AppendFiberFrame(ReqInfoEx<AppendReqCallback> reqInfo, AppendProcessor processor) {
        super("append", processor, reqInfo);
    }

    @Override
    protected FrameCallResult handle(Throwable ex) {
        gc.getRaftStatus().setTruncating(false);
        // to notify RaftUtil.waitWriteFinish() to resume
        gc.getRaftStatus().getLogForceFinishCondition().signalAll();

        log.error("append error", ex);
        writeAppendResp(AppendProcessor.APPEND_SERVER_ERROR, ex.toString());
        return Fiber.frameReturn();
    }

    @Override
    protected FrameCallResult doFinally() {
        gc.getRaftStatus().copyShareStatus();
        AppendReqCallback req = reqInfo.getReqFrame().getBody();
        if (needRelease) {
            RaftUtil.release(req.getLogs());
        }
        return Fiber.frameReturn();
    }

    @Override
    protected int getLeaderId() {
        return reqInfo.getReqFrame().getBody().getLeaderId();
    }

    @Override
    protected int getRemoteTerm() {
        return reqInfo.getReqFrame().getBody().getTerm();
    }

    @Override
    protected FrameCallResult process() {
        AppendReqCallback req = reqInfo.getReqFrame().getBody();
        RaftStatusImpl raftStatus = gc.getRaftStatus();
        if (reqInfo.getReqContext().getTimeout().isTimeout(raftStatus.getTs())) {
            // not generate response
            return Fiber.frameReturn();
        }
        if (raftStatus.isInstallSnapshot()) {
            writeAppendResp(AppendProcessor.APPEND_INSTALL_SNAPSHOT, null);
            return Fiber.frameReturn();
        }
        if (req.getPrevLogIndex() != raftStatus.getLastLogIndex() || req.getPrevLogTerm() != raftStatus.getLastLogTerm()) {
            log.info("log not match. prevLogIndex={}, localLastLogIndex={}, prevLogTerm={}, localLastLogTerm={}, leaderId={}, groupId={}",
                    req.getPrevLogIndex(), raftStatus.getLastLogIndex(), req.getPrevLogTerm(),
                    raftStatus.getLastLogTerm(), req.getLeaderId(), raftStatus.getGroupId());
            int currentTerm = raftStatus.getCurrentTerm();
            Supplier<Boolean> cancelIndicator = () -> raftStatus.getCurrentTerm() != currentTerm;
            FiberFrame<Pair<Integer, Long>> replicatePosFrame = gc.getRaftLog().tryFindMatchPos(
                    req.getPrevLogTerm(), req.getPrevLogIndex(), cancelIndicator);
            return Fiber.call(replicatePosFrame, pos -> resumeWhenFindReplicatePosFinish(pos, currentTerm));
        }
        return doAppend(reqInfo, gc);
    }

    private FrameCallResult doAppend(ReqInfoEx<AppendReqCallback> reqInfo, GroupComponents gc) {
        AppendReqCallback req = reqInfo.getReqFrame().getBody();
        RaftStatusImpl raftStatus = gc.getRaftStatus();
        if (req.getPrevLogIndex() < raftStatus.getCommitIndex()) {
            BugLog.getLog().error("leader append request prevLogIndex less than local commit index. leaderId={}, prevLogIndex={}, commitIndex={}, groupId={}",
                    req.getLeaderId(), req.getPrevLogIndex(), raftStatus.getCommitIndex(), raftStatus.getGroupId());
            writeAppendResp(AppendProcessor.APPEND_PREV_LOG_INDEX_LESS_THAN_LOCAL_COMMIT, null);
            return Fiber.frameReturn();
        }
        ArrayList<LogItem> logs = req.getLogs();
        if (logs == null || logs.isEmpty()) {
            log.error("bad request: no logs");
            writeAppendResp(AppendProcessor.APPEND_REQ_ERROR, null);
            return Fiber.frameReturn();
        }

        if (req.getLeaderCommit() < raftStatus.getCommitIndex()) {
            log.info("leader commitIndex less than local, maybe leader restart recently. leaderId={}, leaderTerm={}, leaderCommitIndex={}, localCommitIndex={}, groupId={}",
                    req.getLeaderId(), req.getTerm(), req.getLeaderCommit(), raftStatus.getCommitIndex(), raftStatus.getGroupId());
        }
        if (req.getLeaderCommit() > raftStatus.getLeaderCommit()) {
            raftStatus.setLeaderCommit(req.getLeaderCommit());
        }

        long index = LinearTaskRunner.lastIndex(raftStatus);

        needRelease = false;

        ArrayList<RaftTask> list = new ArrayList<>(logs.size());
        for (int i = 0, len = logs.size(); i < len; i++) {
            LogItem li = logs.get(i);
            if (++index != li.getIndex()) {
                log.error("bad request: log index not match. index={}, expectIndex={}, leaderId={}, groupId={}",
                        li.getIndex(), index, req.getLeaderId(), raftStatus.getGroupId());
                writeAppendResp(AppendProcessor.APPEND_REQ_ERROR, "log index not match");
                return Fiber.frameReturn();
            }
            RaftInput raftInput = new RaftInput(li.getBizType(), li.getHeader(), li.getBody(), null,
                    li.getType() == LogItem.TYPE_LOG_READ);
            RaftTask task = new RaftTask(raftStatus.getTs(), li.getType(), raftInput, null);
            task.setItem(li);
            list.add(task);

            if (index < raftStatus.getGroupReadyIndex()) {
                raftStatus.setGroupReadyIndex(index);
            }
            if (i == len - 1) {
                registerRespWriter(raftStatus, index);
            }
        }
        gc.getLinearTaskRunner().submitTasks(raftStatus, list);

        // success response write in CommitManager fiber
        return Fiber.frameReturn();
    }

    private void registerRespWriter(RaftStatusImpl raftStatus, long index) {
        int term = raftStatus.getCurrentTerm();
        // register write response callback
        gc.getCommitManager().registerRespWriter(lastPersistIndex -> {
            if (raftStatus.getCurrentTerm() == term) {
                if (lastPersistIndex >= index) {
                    writeAppendResp(AppendProcessor.APPEND_SUCCESS, null);
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        });
    }

    private FrameCallResult resumeWhenFindReplicatePosFinish(Pair<Integer, Long> pos, int oldTerm) {
        GroupComponents gc = reqInfo.getRaftGroup().getGroupComponents();
        RaftStatusImpl raftStatus = gc.getRaftStatus();
        if (oldTerm != raftStatus.getCurrentTerm()) {
            log.info("term changed when find replicate pos, ignore result. oldTerm={}, newTerm={}, groupId={}",
                    oldTerm, raftStatus.getCurrentTerm(), raftStatus.getGroupId());
            writeAppendResp(AppendProcessor.APPEND_SERVER_ERROR, "term changed");
            return Fiber.frameReturn();
        }
        if (reqInfo.getReqContext().getTimeout().isTimeout(raftStatus.getTs())) {
            // not generate response
            return Fiber.frameReturn();
        }
        AppendReqCallback req = reqInfo.getReqFrame().getBody();
        if (pos == null) {
            log.info("follower has no suggest index, will install snapshot. groupId={}", raftStatus.getGroupId());
            writeAppendResp(AppendProcessor.APPEND_LOG_NOT_MATCH, null);
            return Fiber.frameReturn();
        } else if (pos.getLeft() == req.getPrevLogTerm() && pos.getRight() == req.getPrevLogIndex()) {
            log.info("local log truncate to prevLogIndex={}, prevLogTerm={}, groupId={}",
                    req.getPrevLogIndex(), req.getPrevLogTerm(), raftStatus.getGroupId());
            if (RaftUtil.writeNotFinished(raftStatus)) {
                // resume to this::execute to re-run all check
                return RaftUtil.waitWriteFinish(raftStatus, this);
            } else {
                return truncateAndAppend(req.getPrevLogIndex(), req.getPrevLogTerm());
            }
        } else {
            log.info("follower suggest term={}, index={}, groupId={}", pos.getLeft(), pos.getRight(), raftStatus.getGroupId());
            writeAppendResp(AppendProcessor.APPEND_LOG_NOT_MATCH, pos.getLeft(), pos.getRight(), null);
            return Fiber.frameReturn();
        }
    }

    private FrameCallResult truncateAndAppend(long matchIndex, int matchTerm) {
        gc.getRaftStatus().setTruncating(true);
        long truncateIndex = matchIndex + 1;
        return Fiber.call(gc.getRaftLog().truncateTail(truncateIndex), v -> afterTruncate(matchIndex, matchTerm));
    }

    private FrameCallResult afterTruncate(long matchIndex, int matchTerm) {
        RaftStatusImpl raftStatus = gc.getRaftStatus();

        raftStatus.setTruncating(false);
        // to notify RaftUtil.waitWriteFinish() to resume
        gc.getRaftStatus().getLogForceFinishCondition().signalAll();

        raftStatus.setLastWriteLogIndex(matchIndex);
        raftStatus.setLastForceLogIndex(matchIndex);
        raftStatus.setLastLogIndex(matchIndex);
        raftStatus.setLastLogTerm(matchTerm);
        return doAppend(reqInfo, gc);
    }

}// end of AppendFiberFrame

class InstallFiberFrame extends AbstractAppendFrame<InstallSnapshotReq> {
    private static final DtLog log = DtLogs.getLogger(InstallFiberFrame.class);

    public InstallFiberFrame(ReqInfoEx<InstallSnapshotReq> reqInfo, AppendProcessor processor) {
        super("install snapshot", processor, reqInfo);
    }

    @Override
    protected FrameCallResult handle(Throwable ex) throws Throwable {
        log.error("install snapshot error", ex);
        return writeAppendResp(AppendProcessor.APPEND_SERVER_ERROR, ex.toString());
    }

    @Override
    protected FrameCallResult doFinally() {
        InstallSnapshotReq req = reqInfo.getReqFrame().getBody();
        GroupComponents gc = reqInfo.getRaftGroup().getGroupComponents();
        gc.getRaftStatus().copyShareStatus();
        req.release();
        return Fiber.frameReturn();
    }

    @Override
    protected int getLeaderId() {
        return reqInfo.getReqFrame().getBody().leaderId;
    }

    protected int getRemoteTerm() {
        return reqInfo.getReqFrame().getBody().term;
    }

    @Override
    protected FrameCallResult process() throws Exception {
        RaftStatusImpl raftStatus = gc.getRaftStatus();
        InstallSnapshotReq req = reqInfo.getReqFrame().getBody();
        if (req.offset == 0 && req.members != null) {
            return startInstall(raftStatus);
        }
        return doInstall(raftStatus, req);
    }

    private FrameCallResult startInstall(RaftStatusImpl raftStatus) throws Exception {
        if (RaftUtil.writeNotFinished(raftStatus)) {
            return RaftUtil.waitWriteFinish(raftStatus, this);
        }
        return Fiber.call(gc.getRaftLog().beginInstall(), this:: applyConfigChange);
    }

    private FrameCallResult applyConfigChange(Void unused) {
        MemberManager mm = reqInfo.getRaftGroup().getGroupComponents().getMemberManager();
        InstallSnapshotReq req = reqInfo.getReqFrame().getBody();

        reqInfo.getRaftGroup().getGroupComponents().getRaftStatus().setLastConfigChangeIndex(req.lastIncludedIndex);

        FiberFrame<Void> f = mm.applyConfigFrame("install snapshot config change",
                req.members, req.observers, req.preparedMembers, req.preparedObservers);
        return Fiber.call(f, v -> writeResp(null));
    }

    private FrameCallResult doInstall(RaftStatusImpl raftStatus, InstallSnapshotReq req) {
        boolean finish = req.done;
        ByteBuffer buf = req.data == null ? null : req.data.getBuffer();
        FiberFuture<Void> f = gc.getStateMachine().installSnapshot(req.lastIncludedIndex,
                req.lastIncludedTerm, req.offset, finish, buf);
        if (finish) {
            return f.await(v -> finishInstall(req, raftStatus));
        } else {
            f.registerCallback((v, ex) -> writeResp(ex));
            return Fiber.frameReturn();
        }
    }

    private FrameCallResult finishInstall(InstallSnapshotReq req, RaftStatusImpl raftStatus) throws Exception {
        raftStatus.setInstallSnapshot(false);

        // call raftStatus.copyShareStatus() in doFinally()
        raftStatus.setLastApplied(req.lastIncludedIndex);
        raftStatus.setLastAppliedTerm(req.lastIncludedTerm);
        raftStatus.setLastApplying(req.lastIncludedIndex);

        raftStatus.setCommitIndex(req.lastIncludedIndex);

        raftStatus.setLastLogTerm(req.lastIncludedTerm);
        raftStatus.setLastLogIndex(req.lastIncludedIndex);
        raftStatus.setLastWriteLogIndex(req.lastIncludedIndex);
        raftStatus.setLastForceLogIndex(req.lastIncludedIndex);

        FiberFrame<Void> finishFrame = gc.getRaftLog().finishInstall(
                req.lastIncludedIndex + 1, req.nextWritePos);
        return Fiber.call(finishFrame, v -> writeResp(null));
    }

    private FrameCallResult writeResp(Throwable ex) {
        if (ex == null) {
            return writeAppendResp(AppendProcessor.APPEND_SUCCESS, null);
        } else {
            return writeAppendResp(AppendProcessor.APPEND_SERVER_ERROR, ex.toString());
        }
    }
}







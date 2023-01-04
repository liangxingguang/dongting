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

import com.github.dtprj.dongting.net.CmdCodes;
import com.github.dtprj.dongting.net.Decoder;
import com.github.dtprj.dongting.net.PbZeroCopyDecoder;
import com.github.dtprj.dongting.net.ProcessContext;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.net.ReqProcessor;
import com.github.dtprj.dongting.net.WriteFrame;
import com.github.dtprj.dongting.pb.PbCallback;
import com.github.dtprj.dongting.raft.impl.RaftStatus;
import com.github.dtprj.dongting.raft.impl.RaftThread;

/**
 * @author huangli
 */
public class AppendProcessor extends ReqProcessor {

    private final RaftStatus raftStatus;

    private PbZeroCopyDecoder decoder = new PbZeroCopyDecoder() {
        @Override
        protected PbCallback createCallback(ProcessContext context) {
            return new AppendReqCallback();
        }
    };

    public AppendProcessor(RaftStatus raftStatus) {
        this.raftStatus = raftStatus;
    }

    @Override
    public WriteFrame process(ReadFrame rf, ProcessContext context) {
        AppendRespWriteFrame resp = new AppendRespWriteFrame();
        AppendReqCallback req = (AppendReqCallback) rf.getBody();
        int remoteTerm = req.getTerm();
        RaftStatus raftStatus = this.raftStatus;
        if (remoteTerm >= raftStatus.getCurrentTerm()) {
            if (remoteTerm > raftStatus.getCurrentTerm()) {
                RaftThread.updateTermAndConvertToFollower(remoteTerm, raftStatus);
            }
            raftStatus.setLastLeaderActiveTime(System.nanoTime());
            resp.setSuccess(true);
        } else {
            resp.setSuccess(false);
        }
        resp.setTerm(raftStatus.getCurrentTerm());
        resp.setRespCode(CmdCodes.SUCCESS);
        return resp;
    }

    @Override
    public Decoder getDecoder() {
        return decoder;
    }
}

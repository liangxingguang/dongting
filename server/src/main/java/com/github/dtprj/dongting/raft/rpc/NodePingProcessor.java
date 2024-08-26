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

import com.github.dtprj.dongting.codec.DecoderCallback;
import com.github.dtprj.dongting.codec.PbNoCopyDecoderCallback;
import com.github.dtprj.dongting.net.ReadPacket;
import com.github.dtprj.dongting.net.ReqContext;
import com.github.dtprj.dongting.net.ReqProcessor;
import com.github.dtprj.dongting.net.WritePacket;

import java.util.UUID;

/**
 * @author huangli
 */
public class NodePingProcessor extends ReqProcessor<NodePingCallback> {

    public static final PbNoCopyDecoderCallback<NodePingCallback> DECODER = new PbNoCopyDecoderCallback<>(ctx -> new NodePingCallback());
    private final int selfNodeId;
    private final UUID uuid;

    public NodePingProcessor(int selfNodeId, UUID uuid) {
        this.selfNodeId = selfNodeId;
        this.uuid = uuid;
    }

    @Override
    public WritePacket process(ReadPacket<NodePingCallback> packet, ReqContext reqContext) {
        return new NodePingWritePacket(selfNodeId, uuid);
    }

    @Override
    public DecoderCallback<NodePingCallback> createDecoder(int command) {
        return DECODER;
    }
}

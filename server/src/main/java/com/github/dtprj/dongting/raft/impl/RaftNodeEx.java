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

import com.github.dtprj.dongting.net.HostPort;
import com.github.dtprj.dongting.net.Peer;
import com.github.dtprj.dongting.raft.RaftNode;

/**
 * @author huangli
 */
public class RaftNodeEx extends RaftNode {

    public final boolean self;

    public boolean pinging;

    public volatile NodeStatus status = new NodeStatus(false, 0);

    public RaftNodeEx(int id, HostPort hostPort, boolean self, Peer peer) {
        super(id, hostPort, peer);
        this.self = self;
    }
}

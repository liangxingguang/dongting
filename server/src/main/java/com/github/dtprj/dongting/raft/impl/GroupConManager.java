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
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.Decoder;
import com.github.dtprj.dongting.net.HostPort;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.PbZeroCopyDecoder;
import com.github.dtprj.dongting.net.Peer;
import com.github.dtprj.dongting.net.ProcessContext;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.net.ReqProcessor;
import com.github.dtprj.dongting.net.StringFieldDecoder;
import com.github.dtprj.dongting.net.WriteFrame;
import com.github.dtprj.dongting.net.ZeroCopyWriteFrame;
import com.github.dtprj.dongting.pb.PbCallback;
import com.github.dtprj.dongting.pb.PbUtil;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * @author huangli
 */
public class GroupConManager {
    private static final DtLog log = DtLogs.getLogger(GroupConManager.class);
    private final UUID uuid = UUID.randomUUID();
    private final byte[] serversStr;
    private final RaftServerConfig config;
    private final NioClient client;
    private final Executor executor;

    // only read/write by raft thread or init thread
    private List<RaftNode> servers = new ArrayList<>();

    private static final PbZeroCopyDecoder DECODER = new PbZeroCopyDecoder() {
        @Override
        protected PbCallback createCallback(ProcessContext context) {
            return new RaftPingFrameCallback(context.getIoThreadStrDecoder());
        }
    };

    private final ReqProcessor processor = new ReqProcessor() {
        @Override
        public WriteFrame process(ReadFrame frame, ProcessContext context) {
            return new RaftPingWriteFrame();
        }

        @Override
        public Decoder getDecoder() {
            return DECODER;
        }
    };

    public GroupConManager(RaftServerConfig config, NioClient client, Executor executor) {
        this.serversStr = config.getServers().getBytes(StandardCharsets.UTF_8);
        this.config = config;
        this.client = client;
        this.executor = executor;
    }

    public static Set<HostPort> parseServers(String serversStr) {
        Set<HostPort> servers = Arrays.stream(serversStr.split("[,;]"))
                .filter(Objects::nonNull)
                .map(s -> {
                    String[] arr = s.split(":");
                    if (arr.length != 2) {
                        throw new IllegalArgumentException("not 'host:port' format:" + s);
                    }
                    return new HostPort(arr[0].trim(), Integer.parseInt(arr[1].trim()));
                }).collect(Collectors.toSet());
        if (servers.size() == 0) {
            throw new RaftException("servers list is empty");
        }
        return servers;
    }

    private RaftNode find(List<RaftNode> servers, HostPort hostPort) {
        for (RaftNode node : servers) {
            if (node.getPeer().getEndPoint().equals(hostPort)) {
                return node;
            }
        }
        return null;
    }

    private CompletableFuture<List<RaftNode>> add(Collection<HostPort> endPoints) {
        HashSet<HostPort> validEndPoints = new HashSet<>();
        for (HostPort hp : endPoints) {
            if (find(servers, hp) == null) {
                validEndPoints.add(hp);
            }
        }
        if (validEndPoints.size() == 0) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        ArrayList<CompletableFuture<Peer>> list = new ArrayList<>();
        for (HostPort hp : validEndPoints) {
            list.add(client.addPeer(hp));
        }
        return CompletableFuture.allOf(list.toArray(new CompletableFuture[0]))
                .thenApply(v -> list.stream().map(f -> new RaftNode(f.join()))
                        .collect(Collectors.toList()));
    }

    public void addSync(Collection<HostPort> endPoints) {
        CompletableFuture<List<RaftNode>> result = add(endPoints);
        List<RaftNode> list = result.join();
        for (RaftNode node : list) {
            if (find(servers, node.getPeer().getEndPoint()) == null) {
                servers.add(node);
            }
        }
    }

    public void pingAllAndUpdateServers() {
        for (RaftNode node : servers) {
            if (node.isSelf()) {
                continue;
            }
            CompletableFuture<RaftNode> f = connectAndPing(node);
            f.handleAsync((nodeCopy, ex) -> {
                if (ex == null) {
                    processPingInRaftThread(node, nodeCopy);
                } else {
                    // the exception should be handled
                    BugLog.log(ex);
                }
                return null;
            }, executor);
        }
    }

    private void processPingInRaftThread(RaftNode node, RaftNode nodeCopy) {
        // TODO check
        node.setReady(nodeCopy.isReady());
        node.setId(nodeCopy.getId());
        node.setServers(nodeCopy.getServers());
    }

    public CompletableFuture<RaftNode> connectAndPing(RaftNode node) {
        RaftNode nodeCopy = node.clone();
        if (nodeCopy.isSelf()) {
            return CompletableFuture.completedFuture(nodeCopy);
        } else {
            CompletableFuture<Void> connectFuture;
            if (nodeCopy.getPeer().isConnected()) {
                connectFuture = CompletableFuture.completedFuture(null);
            } else {
                connectFuture = client.connect(nodeCopy.getPeer());
            }
            return connectFuture.thenCompose(v -> sendRaftPing(nodeCopy))
                    .exceptionally(ex -> {
                        log.info("connect to raft server {} fail: {}",
                                nodeCopy.getPeer().getEndPoint(), ex.getMessage());
                        return nodeCopy;
                    });
        }
    }

    private CompletableFuture<RaftNode> sendRaftPing(RaftNode nodeCopy) {
        DtTime timeout = new DtTime(config.getRpcTimeout(), TimeUnit.MILLISECONDS);
        CompletableFuture<ReadFrame> f = client.sendRequest(nodeCopy.getPeer(), new RaftPingWriteFrame(), DECODER, timeout);
        return f.handle((rf, ex) -> {
            if (ex == null) {
                whenRpcFinish(rf, nodeCopy);
            } else {
                log.info("init raft connection {} fail: {}", nodeCopy.getPeer().getEndPoint(), ex.getMessage());
            }
            return nodeCopy;
        });
    }

    private void whenRpcFinish(ReadFrame rf, RaftNode nodeCopy) {
        RaftPingFrameCallback callback = (RaftPingFrameCallback) rf.getBody();
        Set<HostPort> remoteServers = null;
        try {
            remoteServers = parseServers(callback.serversStr);
        } catch (Exception e) {
            log.error("servers list is empty", e);
        }
        boolean self = callback.uuidHigh == uuid.getMostSignificantBits()
                && callback.uuidLow == uuid.getLeastSignificantBits();
        if (remoteServers != null) {
            nodeCopy.setServers(Collections.unmodifiableSet(remoteServers));
        }
        nodeCopy.setId(callback.id);
        nodeCopy.setReady(true);
        nodeCopy.setSelf(self);

        if (!self) {
            log.info("init raft connection success: remote={}, id={}, servers={}",
                    nodeCopy.getPeer().getEndPoint(), nodeCopy.getId(), callback.serversStr);
        }
    }

    private void checkNodes(List<RaftNode> list) {
        int size = list.size();
        boolean findSelf = false;
        for (int i = 0; i < size; i++) {
            RaftNode rn1 = list.get(i);
            if (!rn1.isReady()) {
                continue;
            }
            if (rn1.isSelf()) {
                findSelf = true;
                client.disconnect(rn1.getPeer());
            }
            for (int j = i + 1; j < size; j++) {
                RaftNode rn2 = list.get(j);
                if (!rn2.isReady()) {
                    continue;
                }
                if (rn1.getId() == rn2.getId()) {
                    throw new RaftException("find same id: " + rn1.getId() + ", "
                            + rn1.getPeer().getEndPoint() + ", " + rn2.getPeer().getEndPoint());
                }
                if (!rn1.getServers().equals(rn2.getServers())) {
                    throw new RaftException("server list not same: "
                            + rn1.getPeer().getEndPoint() + ", " + rn2.getPeer().getEndPoint());
                }
            }
        }
        if (!findSelf) {
            throw new RaftException("can't init raft connection to self");
        }
    }

    private CompletableFuture<List<RaftNode>> pingAll() {
        ArrayList<CompletableFuture<RaftNode>> futures = new ArrayList<>();
        List<RaftNode> servers = this.servers;
        for (RaftNode server : servers) {
            CompletableFuture<RaftNode> initFuture = connectAndPing(server);
            futures.add(initFuture);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    @SuppressWarnings("BusyWait")
    public void initRaftGroup(int electQuorum, Collection<HostPort> servers, int sleepMillis) {
        while (true) {
            try {
                addSync(servers);
                CompletableFuture<List<RaftNode>> f = this.pingAll();
                List<RaftNode> nodes = f.get(config.getLeaderTimeout(), TimeUnit.MILLISECONDS);
                checkNodes(nodes);
                long currentNodes = nodes.stream().filter(RaftNode::isReady).count();
                if (currentNodes >= electQuorum) {
                    log.info("raft group init success. electQuorum={}, currentNodes={}. remote peers: {}",
                            electQuorum, currentNodes, nodes);
                    this.servers = nodes;
                    return;
                }
                Thread.sleep(sleepMillis);
            } catch (TimeoutException e) {
                log.warn("init raft group timeout, will continue");
            } catch (Exception e) {
                throw new RaftException(e);
            }
        }
    }

    public ReqProcessor getProcessor() {
        return processor;
    }

    public List<RaftNode> getServers() {
        return servers;
    }

    class RaftPingWriteFrame extends ZeroCopyWriteFrame {

        public RaftPingWriteFrame() {
            setCommand(Commands.RAFT_PING);
        }

        @Override
        protected int accurateBodySize() {
            return PbUtil.accurateFix32Size(1, config.getId())
                    + PbUtil.accurateFix64Size(2, uuid.getMostSignificantBits())
                    + PbUtil.accurateFix64Size(3, uuid.getLeastSignificantBits())
                    + PbUtil.accurateLengthDelimitedSize(4, serversStr.length);
        }

        @Override
        protected void encodeBody(ByteBuffer buf) {
            PbUtil.writeFix32(buf, 1, config.getId());
            PbUtil.writeFix64(buf, 2, uuid.getMostSignificantBits());
            PbUtil.writeFix64(buf, 3, uuid.getLeastSignificantBits());
            PbUtil.writeBytes(buf, 4, serversStr);
        }
    }


    static class RaftPingFrameCallback extends PbCallback {
        private final StringFieldDecoder ioThreadStrDecoder;
        private int id;
        private long uuidHigh;
        private long uuidLow;
        private String serversStr;

        public RaftPingFrameCallback(StringFieldDecoder ioThreadStrDecoder) {
            this.ioThreadStrDecoder = ioThreadStrDecoder;
        }

        @Override
        public boolean readFix32(int index, int value) {
            if (index == 1) {
                this.id = value;
            }
            return true;
        }

        @Override
        public boolean readFix64(int index, long value) {
            if (index == 2) {
                this.uuidHigh = value;
            } else if (index == 3) {
                this.uuidLow = value;
            }
            return true;
        }

        @Override
        public boolean readBytes(int index, ByteBuffer buf, int len, boolean begin, boolean end) {
            if (index == 4) {
                serversStr = ioThreadStrDecoder.decodeUTF8(buf, len, begin, end);
            }
            return true;
        }

        @Override
        public Object getResult() {
            return this;
        }
    }

}
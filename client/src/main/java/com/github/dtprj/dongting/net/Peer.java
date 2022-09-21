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
package com.github.dtprj.dongting.net;

/**
 * @author huangli
 */
class Peer {
    private final Object endPoint;
    private final boolean incoming;
    private volatile DtChannel dtChannel;

    public Peer(Object endPoint, boolean incoming) {
        this.endPoint = endPoint;
        this.incoming = incoming;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(endPoint).append(", incoming=").append(incoming).append(",worker=");
        DtChannel dtc = this.dtChannel;
        if (dtc == null) {
            sb.append("null");
        } else {
            sb.append(dtc.getWorkerName());
        }
        return sb.toString();
    }

    public Object getEndPoint() {
        return endPoint;
    }

    public boolean isIncoming() {
        return incoming;
    }

    public DtChannel getDtChannel() {
        return dtChannel;
    }

    public void setDtChannel(DtChannel dtChannel) {
        this.dtChannel = dtChannel;
    }
}

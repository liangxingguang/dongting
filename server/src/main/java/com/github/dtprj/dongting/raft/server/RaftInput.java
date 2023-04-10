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
package com.github.dtprj.dongting.raft.server;

import com.github.dtprj.dongting.buf.RefByteBuffer;
import com.github.dtprj.dongting.common.DtTime;

/**
 * @author huangli
 */
public class RaftInput {
    private final DtTime deadline;
    private final boolean readOnly;
    private final RefByteBuffer logData;
    private final Object input;
    private final int size;

    public RaftInput(RefByteBuffer logData, Object input, DtTime deadline, boolean readOnly) {
        this.logData = logData;
        this.input = input;
        this.deadline = deadline;
        this.readOnly = readOnly;
        this.size = logData == null ? 0 : logData.getBuffer().remaining();
    }

    /**
     * return size for flow control.
     */
    public int size() {
        return size;
    }

    public DtTime getDeadline() {
        return deadline;
    }

    public RefByteBuffer getLogData() {
        return logData;
    }

    public Object getInput() {
        return input;
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}

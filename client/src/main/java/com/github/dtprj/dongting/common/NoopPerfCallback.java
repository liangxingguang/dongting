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
package com.github.dtprj.dongting.common;

/**
 * @author huangli
 */
public final class NoopPerfCallback extends PerfCallback {

    public static final NoopPerfCallback INSTANCE = new NoopPerfCallback();

    private NoopPerfCallback() {
        super(false);
    }

    public long takeTime(int perfType) {
        return 0;
    }

    public long takeTime(int perfType, Timestamp ts) {
        return 0;
    }

    @Override
    public void fire(int perfType) {
    }

    @Override
    public void fire(int perfType, int count, long sum) {
    }

    @Override
    public void fireTime(int perfType, long startTime) {
    }

    @Override
    public void fireTime(int perfType, long startTime, int count, long sum) {
    }

    @Override
    public void fireTime(int perfType, long startTime, int count, long sum, Timestamp ts) {
    }

    @Override
    public boolean accept(int perfType) {
        return false;
    }

    @Override
    public void onEvent(int perfType, long costTime, int count, long sum) {
    }
}

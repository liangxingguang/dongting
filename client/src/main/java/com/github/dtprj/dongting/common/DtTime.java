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

import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public class DtTime {
    private final long createTime = System.nanoTime();
    private final long deadline;

    public DtTime() {
        this.deadline = createTime;
    }

    public DtTime(long timeout, TimeUnit unit) {
        this.deadline = createTime + unit.toNanos(timeout);
    }

    public long elapse(TimeUnit unit) {
        return unit.convert(System.nanoTime() - createTime, TimeUnit.NANOSECONDS);
    }

    public long rest(TimeUnit unit) {
        return unit.convert(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    public long getTimeout(TimeUnit unit) {
        return unit.convert(deadline - createTime, TimeUnit.NANOSECONDS);
    }
}

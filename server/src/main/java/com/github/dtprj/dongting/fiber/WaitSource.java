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
package com.github.dtprj.dongting.fiber;

import java.util.LinkedList;

/**
 * @author huangli
 */
abstract class WaitSource {
    protected final String name;
    LinkedList<Fiber> waiters;
    protected final FiberGroup fiberGroup;

    public WaitSource(String name, FiberGroup group) {
        this.fiberGroup = group;
        this.name = name;
    }

    protected abstract void prepare(Fiber waitFiber, boolean timeout);

    boolean signal0(boolean addFirst) {
        if (fiberGroup.finished) {
            return false;
        }
        Fiber f;
        if (waiters != null && (f = waiters.pollFirst()) != null) {
            signalFiber(f, addFirst);
            return true;
        } else {
            return false;
        }
    }

    void signalFiber(Fiber f, boolean addFirst) {
        if (f.scheduleTimeoutMillis > 0) {
            fiberGroup.dispatcher.removeFromScheduleQueue(f);
        }
        prepare(f, false);
        f.cleanSchedule();
        fiberGroup.tryMakeFiberReady(f, addFirst);
    }

    void signalAll0(boolean addFirst) {
        if (fiberGroup.finished) {
            return;
        }
        LinkedList<Fiber> waiters = this.waiters;
        if (waiters == null) {
            return;
        }
        Fiber f;
        if (addFirst) {
            while ((f = waiters.pollLast()) != null) {
                signalFiber(f, true);
            }
        } else {
            while ((f = waiters.pollFirst()) != null) {
                signalFiber(f, false);
            }
        }
    }

    public FiberGroup getGroup() {
        return fiberGroup;
    }

    public String getName() {
        return name;
    }
}

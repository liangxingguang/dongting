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

/**
 * @author huangli
 */
public class FiberCondition extends WaitSource {

    FiberCondition(String name, FiberGroup group) {
        super(name, group);
    }

    @Override
    public String toString() {
        return "Condition:" + name + "@" + Integer.toHexString(hashCode());
    }

    @Override
    protected void prepare(Fiber waitFiber, boolean timeout) {
        FiberCondition[] cs = waitFiber.sourceConditions;
        if (cs != null) {
            for (FiberCondition c : cs) {
                if (c != this) {
                    c.waiters.remove(waitFiber);
                }
            }
        }
        waitFiber.source = null;
        waitFiber.sourceConditions = null;
    }

    public void signal() {
        Dispatcher.getCurrentFiberAndCheck(fiberGroup);
        signal0(true);
    }

    public void signalLater() {
        Dispatcher.getCurrentFiberAndCheck(fiberGroup);
        signal0(false);
    }

    public void signal(Fiber targetFiber) {
        Dispatcher.getCurrentFiberAndCheck(fiberGroup);
        if (fiberGroup.finished) {
            return;
        }
        if (targetFiber.source != this) {
            return;
        }
        // assert waiters not null
        waiters.remove(targetFiber);
        signalFiber(targetFiber, true);
    }

    public void signalAll() {
        Dispatcher.getCurrentFiberAndCheck(fiberGroup);
        signalAll0(true);
    }

    public FrameCallResult await(FrameCall<Void> resumePoint) {
        return Dispatcher.awaitOn(this, -1, resumePoint);
    }

    public FrameCallResult await(long millis, FrameCall<Void> resumePoint) {
        return Dispatcher.awaitOn(this, millis, resumePoint);
    }

    public FrameCallResult await(FiberCondition another, FrameCall<Void> resumePoint) {
        return await(-1, another, resumePoint);
    }

    public FrameCallResult await(long millis, FiberCondition another, FrameCall<Void> resumePoint) {
        if (another == this) {
            throw new IllegalArgumentException("same condition");
        }
        if (another.fiberGroup != this.fiberGroup) {
            throw new IllegalArgumentException("not in same group");
        }
        return Dispatcher.awaitOn(new FiberCondition[]{this, another}, millis, resumePoint);
    }
}

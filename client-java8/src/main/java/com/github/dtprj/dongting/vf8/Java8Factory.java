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
package com.github.dtprj.dongting.vf8;

import com.github.dtprj.dongting.common.AbstractRefCountUpdater;
import com.github.dtprj.dongting.common.DtException;
import com.github.dtprj.dongting.common.VersionFactory;
import com.github.dtprj.dongting.java8.Java8MpscLinkedQueue;
import com.github.dtprj.dongting.java8.Java8RefCountUpdater;
import com.github.dtprj.dongting.java8.PlainRefCountUpdater;
import com.github.dtprj.dongting.queue.MpscLinkedQueue;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * @author huangli
 */
@SuppressWarnings("unused")
public class Java8Factory extends VersionFactory {

    private static final Unsafe unsafe;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new DtException(e);
        }
    }

    @Override
    public AbstractRefCountUpdater newRefCountUpdater(boolean plain) {
        return plain ? PlainRefCountUpdater.getInstance() : Java8RefCountUpdater.getInstance();
    }

    @Override
    public <E> MpscLinkedQueue<E> newMpscLinkedQueue() {
        return new Java8MpscLinkedQueue<>();
    }

    @Override
    public void releaseFence() {
        unsafe.storeFence();
    }

    @Override
    public void acquireFence() {
        unsafe.loadFence();
    }

    @Override
    public void fullFence() {
        unsafe.fullFence();
    }
}
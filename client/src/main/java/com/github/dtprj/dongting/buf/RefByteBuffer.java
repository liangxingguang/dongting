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
package com.github.dtprj.dongting.buf;

import com.github.dtprj.dongting.common.RefCount;

import java.nio.ByteBuffer;

/**
 * @author huangli
 */
public class RefByteBuffer extends RefCount {

    private final ByteBuffer buffer;
    private final ByteBufferPool pool;

    protected RefByteBuffer(boolean plain, ByteBufferPool pool, int requestSize, int threshold) {
        super(plain);
        if (requestSize < threshold) {
            this.buffer = ByteBuffer.allocate(requestSize);
            this.pool = null;
        } else {
            this.buffer = pool.borrow(requestSize);
            this.pool = pool;
        }
    }

    /**
     * create thread safe instance.
     */
    public static RefByteBuffer create(ByteBufferPool pool, int requestSize, int threshold) {
        return new RefByteBuffer(false, pool, requestSize, threshold);
    }

    /**
     * create instance which is not thread safe.
     */
    public static RefByteBuffer createPlain(ByteBufferPool pool, int requestSize, int threshold) {
        return new RefByteBuffer(true, pool, requestSize, threshold);
    }

    @Override
    public void retain(int increment) {
        if (pool == null) {
            return;
        }
        super.retain(increment);
    }

    @Override
    public boolean release(int decrement) {
        if (pool == null) {
            return false;
        }
        boolean result = super.release(decrement);
        if (result) {
            pool.release(buffer);
        }
        return result;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }
}
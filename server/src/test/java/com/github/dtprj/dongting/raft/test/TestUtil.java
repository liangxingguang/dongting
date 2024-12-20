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
package com.github.dtprj.dongting.raft.test;

import com.github.dtprj.dongting.buf.ByteBufferPool;
import com.github.dtprj.dongting.buf.DefaultPoolFactory;
import com.github.dtprj.dongting.buf.RefBufferFactory;
import com.github.dtprj.dongting.common.DtException;
import com.github.dtprj.dongting.common.Timestamp;
import org.opentest4j.AssertionFailedError;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author huangli
 */
@SuppressWarnings("unused")
public class TestUtil {
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void waitUtil(Supplier<Boolean> condition) {
        waitUtil(Boolean.TRUE, (Supplier) condition, 5000);
    }

    public static void waitUtil(Object expectValue, Supplier<Object> actual) {
        waitUtil(expectValue, actual, 5000);
    }

    public static void waitUtil(Object expectValue, Supplier<Object> actual, long timeoutMillis) {
        waitUtilInExecutor(null, expectValue, actual, timeoutMillis);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void waitUtilInExecutor(Executor executor, Supplier<Boolean> actual) {
        waitUtilInExecutor(executor, Boolean.TRUE, (Supplier) actual, 5000);
    }

    public static void waitUtilInExecutor(Executor executor, Object expectValue, Supplier<Object> actual) {
        waitUtilInExecutor(executor, expectValue, actual, 5000);
    }

    public static void waitUtilInExecutor(Executor executor, Object expectValue, Supplier<Object> actual, long timeoutMillis) {
        long start = System.nanoTime();
        long deadline = start + timeoutMillis * 1000 * 1000;
        Object obj = getResultInExecutor(executor, actual);
        if (Objects.equals(expectValue, obj)) {
            return;
        }
        int waitCount = 0;
        while (deadline - System.nanoTime() > 0) {
            try {
                //noinspection BusyWait
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            waitCount++;
            obj = getResultInExecutor(executor, actual);
            if (Objects.equals(expectValue, obj)) {
                return;
            }
        }
        throw new AssertionFailedError("expect: " + expectValue + ", actual:" + obj + ", timeout="
                + timeoutMillis + "ms, cost=" + (System.nanoTime() - start) / 1000 / 1000 + "ms, waitCount=" + waitCount);
    }

    public static Object getResultInExecutor(Executor executor, Supplier<Object> actual) {
        if (executor == null) {
            return actual.get();
        }
        CompletableFuture<Object> f = new CompletableFuture<>();
        executor.execute(() -> f.complete(actual.get()));
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final Field TIMESTAMP_NANO_TIME;
    private static final Field TIMESTAMP_WALL_CLOCK_MILLIS;

    static {
        try {
            TIMESTAMP_NANO_TIME = Timestamp.class.getDeclaredField("nanoTime");
            TIMESTAMP_NANO_TIME.setAccessible(true);
            TIMESTAMP_WALL_CLOCK_MILLIS = Timestamp.class.getDeclaredField("wallClockMillis");
            TIMESTAMP_WALL_CLOCK_MILLIS.setAccessible(true);
        } catch (Exception e) {
            throw new DtException(e);
        }
    }

    public static void updateTimestamp(Timestamp ts, long nanoTime, long wallClockMillis) {
        try {
            TIMESTAMP_NANO_TIME.set(ts, nanoTime);
            TIMESTAMP_WALL_CLOCK_MILLIS.set(ts, wallClockMillis);
        } catch (Exception e) {
            throw new DtException(e);
        }
    }

    public static void plus1Hour(Timestamp ts) {
        updateTimestamp(ts, ts.getNanoTime() + Duration.ofHours(1).toNanos(),
                ts.getWallClockMillis() + Duration.ofHours(1).toMillis());
    }

    public static RefBufferFactory heapPool() {
        ByteBufferPool p = new DefaultPoolFactory().createPool(new Timestamp(), false);
        return new RefBufferFactory(p, 0);
    }

    public static ByteBufferPool directPool() {
        return new DefaultPoolFactory().createPool(new Timestamp(), true);
    }

    public static String randomStr(int length) {
        Random r = new Random();
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (r.nextInt(26) + 'a');
        }
        return new String(bytes);
    }
}

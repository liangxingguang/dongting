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
public class NetBizCodeException extends NetException {
    private static final long serialVersionUID = 3202394639245091204L;
    private final int bizCode;

    public NetBizCodeException(int bizCode, String msg) {
        super(msg);
        this.bizCode = bizCode;
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    public int getBizCode() {
        return bizCode;
    }

    @Override
    public String toString() {
        return "receive error from server: bizCode=" + bizCode + ", msg=" + getMessage();
    }
}

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
public abstract class Packet {
    public static final int IDX_TYPE = 1;
    public static final int IDX_COMMAND = 2;
    public static final int IDX_SEQ = 3;
    public static final int IDX_RESP_CODE = 4;
    public static final int IDX_BIZ_CODE = 5;
    public static final int IDX_MSG = 6;
    public static final int IDX_TIMEOUT = 7;
    public static final int IDX_EXTRA = 8;
    public static final int IDX_BODY = 15;

    int packetType;
    int command;
    int seq;
    int respCode;
    int bizCode;
    String msg;
    long timeout;
    byte[] extra;

    @Override
    public String toString() {
        return "Packet(type=" + packetType +
                ",cmd=" + command +
                ",seq=" + seq +
                ",respCode=" + respCode +
                ",bizCode=" + bizCode +
                ")";
    }


    public int getPacketType() {
        return packetType;
    }

    void setPacketType(int packetType) {
        this.packetType = packetType;
    }

    public int getCommand() {
        return command;
    }

    public void setCommand(int command) {
        this.command = command;
    }

    public int getSeq() {
        return seq;
    }

    void setSeq(int seq) {
        this.seq = seq;
    }

    public int getRespCode() {
        return respCode;
    }

    public void setRespCode(int respCode) {
        this.respCode = respCode;
    }

    public int getBizCode() {
        return bizCode;
    }

    public void setBizCode(int bizCode) {
        this.bizCode = bizCode;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    long getTimeout() {
        return timeout;
    }

    void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public byte[] getExtra() {
        return extra;
    }

    public void setExtra(byte[] extra) {
        this.extra = extra;
    }
}

package com.github.dtprj.dongting.net;

/**
 * @author huangli
 */
public interface FrameType {
    int TYPE_REQ = 1;
    int TYPE_RESP = 2;
    int TYPE_ONE_WAY = 3;

    static String toStr(int frameType) {
        switch (frameType) {
            case TYPE_RESP:
                return "RESP";
            case TYPE_REQ:
                return "REQ";
            case TYPE_ONE_WAY:
                return "ONE_WAY";
            default:
                return "UNKNOWN";
        }
    }
}

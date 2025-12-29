package com.github.jsbxyyx.mjar;

public class NativeDecryptHelper {
    public static native byte[] maybeDecrypt(byte[] buf, int offset);
}
package com.github.jsbxyyx.mjar;

public class Mjarencrypt {
    public native byte[] encrypt(byte[] bArr);

    static {
        String suffix;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.indexOf("windows") > -1) {
            suffix = ".dll";
        } else if (os.indexOf("mac") > -1) {
            suffix = ".dylib";
        } else {
            suffix = ".so";
        }
        System.load(System.getProperty("LIB_MJAR_PATH", System.getProperty("user.dir")) + "/libmjar" + suffix);
    }
}
package com.github.jsbxyyx.mjar;

import java.nio.charset.StandardCharsets;

public class Mjarencrypt3 {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -cp mjar.jar com.github.jsbxyyx.mjar.Mjarencrypt3 <string>");
            return;
        }
        Mjarencrypt mjarencrypt = new Mjarencrypt();
        byte[] encrypt = mjarencrypt.encrypt(args[0].getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < encrypt.length; i++) {
            System.out.printf("%02X", encrypt[i]);
        }
        System.out.println();
    }

}

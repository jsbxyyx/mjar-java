package com.github.jsbxyyx.mjar;

import java.nio.charset.StandardCharsets;

public class Mjarencrypt3 {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -cp mjar.jar com.github.jsbxyyx.mjar.Mjarencrypt3 <string>");
            return;
        }
        Mjarencrypt mjarencrypt = new Mjarencrypt();
        mjarencrypt.encrypt(args[0].getBytes(StandardCharsets.UTF_8));
    }

}

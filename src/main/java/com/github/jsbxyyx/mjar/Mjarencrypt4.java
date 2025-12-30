package com.github.jsbxyyx.mjar;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

public class Mjarencrypt4 {
    private static final String SPRING_BOOT_LIB = "BOOT-INF/lib/";
    private static final String WEB_INF_LIB = "WEB-INF/lib/";
    private static final String MAYBE_DECRYPT_METHOD_NAME = "maybeDecrypt";
    private static final String MAYBE_DECRYPT_METHOD_DESC = "([BI)[B";

    private static String targetPkg;
    private static Mjarencrypt encryptor;
    private static boolean isDebug = false;

    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 2) {
            System.out.println("Usage: java -jar mjar.jar <pkg_prefix> <source_jar> [DEBUG]");
            return;
        }

        targetPkg = args[0].replace(".", "/");
        encryptor = new Mjarencrypt();
        File sourceFile = new File(args[1]);
        if (args.length > 2) isDebug = "DEBUG".equalsIgnoreCase(args[2]);

        String outFilename = sourceFile.getName().replace(".jar", "-enc.jar").replace(".war", "-enc.war");
        File outputFile = new File(sourceFile.getParent(), outFilename);
        if (outputFile.exists()) {
            System.out.println("Output file already exists, deleting: " + outputFile.getAbsolutePath());
            outputFile.delete();
        }

        System.out.println("Processing: " + sourceFile.getName());
        System.out.println("/");

        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(outputFile)) {
            processLevel(fis, fos, 0);
        }
        System.out.println("\n>>> Encryption Complete: " + outputFile.getAbsolutePath());
    }

    private static void processLevel(InputStream is, OutputStream os, int depth) throws Exception {
        JarInputStream jis = new JarInputStream(is);
        Manifest manifest = jis.getManifest();
        JarOutputStream jos = (manifest != null) ? new JarOutputStream(os, manifest) : new JarOutputStream(os);

        JarEntry entry;
        while ((entry = jis.getNextJarEntry()) != null) {
            String name = entry.getName();
            if (JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) continue;

            byte[] clazzData;
            boolean isArchive = name.endsWith(".jar") || name.endsWith(".war");

            printTree(name, depth, isArchive);

            if (isArchive) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                processLevel(jis, baos, depth + 1);
                clazzData = baos.toByteArray();
            } else if (name.endsWith(".class")) {
                byte[] classBytes = readAllBytes(jis);
                clazzData = transformClass(name, classBytes);
            } else {
                clazzData = readAllBytes(jis);
            }

            JarEntry newEntry = new JarEntry(name);
            newEntry.setTime(entry.getTime());

            if ((name.startsWith(SPRING_BOOT_LIB) || name.startsWith(WEB_INF_LIB)) && isArchive) {
                newEntry.setMethod(ZipEntry.STORED);
                newEntry.setSize(clazzData.length);
                newEntry.setCompressedSize(clazzData.length);
                newEntry.setCrc(calculateCrc(clazzData));
            } else {
                newEntry.setMethod(ZipEntry.DEFLATED);
            }

            jos.putNextEntry(newEntry);
            jos.write(clazzData);
            jos.closeEntry();
        }
        jos.finish();
        jos.flush();
    }

    private static void printTree(String name, int depth, boolean isArchive) {
        if (isArchive) {
            for (int i = 0; i < depth; i++) {
                System.out.print("│   ");
            }
            System.out.println("├── [A] " + name);
        } else if (name.endsWith(".class")) {
            if (needEncrypt(name) || needPatch(name)) {
                for (int i = 0; i < depth; i++) {
                    System.out.print("│   ");
                }
                System.out.println("├── [C] " + name + (needEncrypt(name) ? " [E]" : "") + (needPatch(name) ? " [P]" : ""));
            }
        } else {
            if (isDebug) {
                for (int i = 0; i < depth; i++) {
                    System.out.print("│   ");
                }
                System.out.println("├── [R]  " + name);
            }
        }
    }

    private static byte[] transformClass(String className, byte[] bytes) {
        if (needEncrypt(className)) {
            return encryptor.encrypt(bytes);
        }

        if (needPatch(className)) {
            String internalName = className.replace(".class", "");
            return patchClassReader(internalName, bytes);
        }

        return bytes;
    }

    private static byte[] patchClassReader(String internalName, byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitEnd() {
                MethodVisitor mv = cv.visitMethod(
                        Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_NATIVE,
                        MAYBE_DECRYPT_METHOD_NAME,
                        MAYBE_DECRYPT_METHOD_DESC,
                        null,
                        null);
                if (mv != null) mv.visitEnd();
                super.visitEnd();
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                // 适配核心构造函数
                if ("<init>".equals(name) && desc.startsWith("([BI")) {
                    return new MethodVisitor(api, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitVarInsn(Opcodes.ILOAD, 2);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    internalName,
                                    MAYBE_DECRYPT_METHOD_NAME,
                                    MAYBE_DECRYPT_METHOD_DESC,
                                    false);
                            mv.visitVarInsn(Opcodes.ASTORE, 1);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                                mv.visitInsn(Opcodes.ACONST_NULL);
                                mv.visitVarInsn(Opcodes.ASTORE, 1);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = is.read(buffer)) != -1) {
            baos.write(buffer, 0, n);
        }
        return baos.toByteArray();
    }

    private static long calculateCrc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static boolean needEncrypt(String className) {
        return className.contains(targetPkg);
    }

    private static boolean needPatch(String className) {
        return className.endsWith("asm/ClassReader.class");
    }
}
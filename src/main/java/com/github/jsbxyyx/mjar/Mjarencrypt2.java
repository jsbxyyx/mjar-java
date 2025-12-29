package com.github.jsbxyyx.mjar;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class Mjarencrypt2 {
    private static final String BOOT_INFO_CLASSES = "/BOOT-INF/classes";
    private static final String BOOT_INFO_LIB = "/BOOT-INF/lib";
    private static final String SPRING_FACTORIES = "META-INF/spring.factories";
    private static final String SPRING_HANDLERS = "META-INF/spring.handlers";
    private static final String SPRING_SCHEMAS = "META-INF/spring.schemas";
    private static final String SERVICES_PATH = "META-INF/services";
    private static final String SPRING_BOOT_PKG = "org.springframework.boot";
    public static final String MANIFEST_MF = "(.*)?META-INF/MANIFEST\\.MF";
    public static final List<String> META_INF_IGNORE_LIST = newList(
            "(.*)?META-INF/.*\\.SF"
            , "(.*)?META-INF/.*\\.sf"
            , "(.*)?META-INF/.*\\.DSA"
            , "(.*)?META-INF/.*\\.dsa"
            , "(.*)?META-INF/.*\\.RSA"
            , "(.*)?META-INF/.*\\.rsa"
            , "(.*)?META-INF/.*\\.EC"
            , "(.*)?META-INF/.*\\.ec");
    public static final String NATIVE_DECRYPT_HELPER_CLASS = "/com/github/jsbxyyx/mjar/NativeDecryptHelper.class";
    public static final Set<String> IGNORE_ENCRYPT_CLASS = newSet(
            NATIVE_DECRYPT_HELPER_CLASS
    );
    private static String pkg;
    private static Mjarencrypt mjarencrypt;
    private static boolean isDebug = false;

    public static void main(String[] args) throws Exception {
        run(args);
    }

    private static void run(String[] args) throws Exception {
        if (args == null || args.length < 2) {
            System.out.println("usage: java -jar mjar.jar com/github/jsbxyyx xx.jar [DEBUG]");
            return;
        }
        System.out.println("args : " + Arrays.toString(args));
        pkg = args[0];
        mjarencrypt = new Mjarencrypt();
        File file = new File(args[1]);
        if (args.length > 2) {
            isDebug = (args[2] != null && args[2].equalsIgnoreCase("DEBUG"));
        }
        String WORK_DIR = normalize(file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(File.separator)));
        String filename = file.getName().substring(0, file.getName().lastIndexOf("."));
        String jarDir = WORK_DIR + "/" + filename;
        File jarDirFile = new File(jarDir);
        if (!jarDirFile.exists()) {
            jarDirFile.mkdirs();
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        JarInputStream stream = new JarInputStream(new ByteArrayInputStream(bytes));
        Manifest manifest = stream.getManifest();
        Attributes mainAttributes = manifest.getMainAttributes();
        String mainClass = mainAttributes.getValue("main-class");
        String startClass = mainAttributes.getValue("start-class");
        System.out.println("main-class : " + mainClass);
        System.out.println("start-class : " + startClass);
        boolean springboot = mainClass.startsWith(SPRING_BOOT_PKG);
        System.out.println("springboot : " + springboot);
        Map<String, byte[]> map = new LinkedHashMap<>();
        parseJar(stream, map, springboot);
        patchSpecifiedClass(map);
        addPatchClass(map);
        writeClass(map, jarDir);
        String encFilename = WORK_DIR + "/" + filename + "-enc.jar";
        writeJar(encFilename, jarDir, manifest);
        System.out.println("clean : " + jarDir);
        clean(new File(jarDir));
        System.out.println("final filename : " + encFilename);
    }

    private static void clean(File f) {
        File[] list;
        if (f.isDirectory() && (list = f.listFiles()) != null) {
            for (File file : list) {
                clean(file);
            }
        }
        f.delete();
    }

    private static void writeJar(String encFilename, String jarDir, Manifest manifest) throws IOException {
        System.out.println("writeJar : " + encFilename);
        FileOutputStream fos = new FileOutputStream(encFilename);
        JarOutputStream target = new JarOutputStream(fos, manifest);
        addToJar(new File(jarDir), jarDir, target);
        target.close();
    }

    private static void addToJar(File source, String prefix, JarOutputStream target) throws IOException {
        String name = normalize(source.getPath());
        String name2 = name.substring(name.indexOf(prefix) + prefix.length());
        if (source.isDirectory()) {
            if (!name2.endsWith("/")) {
                name2 = name2 + "/";
            }
            if (isDebug) {
                System.out.println("add [" + name2.substring(1) + "] to jar.");
            }
            JarEntry entry = new JarEntry(name2.substring(1));
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            target.closeEntry();
            File[] fs = source.listFiles();
            if (fs != null) {
                for (File nestedFile : fs) {
                    addToJar(nestedFile, prefix, target);
                }
            }
            return;
        }
        if (isDebug) {
            System.out.println("add [" + name2.substring(1) + "] to jar.");
        }
        JarEntry entry2 = new JarEntry(name2.substring(1));
        entry2.setTime(source.lastModified());
        target.putNextEntry(entry2);
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
        try {
            byte[] buffer = new byte[1024];
            while (true) {
                int count = in.read(buffer);
                if (count != -1) {
                    target.write(buffer, 0, count);
                } else {
                    target.closeEntry();
                    in.close();
                    return;
                }
            }
        } catch (Throwable th) {
            try {
                in.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private static void patchSpecifiedClass(Map<String, byte[]> map) {
        for (Map.Entry<String, byte[]> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("org/objectweb/asm/ClassReader.class")
                    || key.endsWith("org/springframework/asm/ClassReader.class")) {
                System.out.println("patch ClassReader : " + key);
                byte[] patched = patchClassReader(entry.getValue());
                entry.setValue(patched);
            }
        }
    }

    private static void addPatchClass(Map<String, byte[]> map) {
        String patchClassName = NATIVE_DECRYPT_HELPER_CLASS;
        if (map.containsKey(patchClassName)) {
            System.out.println("NativeDecryptHelper already exists in jar, skip adding.");
            return;
        }
        System.out.println("add patch class : " + patchClassName);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(patchClassName.substring(1));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            map.put(patchClassName, out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to add patch class : " + patchClassName, e);
        }
    }

    private static void parseJar(JarInputStream stream, Map<String, byte[]> map, boolean spring) throws Exception {
        while (true) {
            JarEntry nextEntry = stream.getNextJarEntry();
            if (nextEntry != null) {
                String name = nextEntry.getName();
                ByteArrayOutputStream dataOutput = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                while (true) {
                    int len = stream.read(buf, 0, buf.length);
                    if (len == -1) {
                        break;
                    } else {
                        dataOutput.write(buf, 0, len);
                    }
                }
                byte[] data = dataOutput.toByteArray();
                String key = "/" + name;
                if (spring) {
                    if (key.indexOf(BOOT_INFO_CLASSES) == 0) {
                        key = key.substring(key.indexOf(BOOT_INFO_CLASSES) + BOOT_INFO_CLASSES.length());
                    }
                    if (key.indexOf(BOOT_INFO_LIB) == 0) {
                        key = key.substring(key.indexOf(BOOT_INFO_LIB) + BOOT_INFO_LIB.length());
                    }
                }
                if (key.toLowerCase().endsWith(".jar")) {
                    System.out.println("jar : " + key);
                    JarInputStream is = new JarInputStream(new ByteArrayInputStream(data));
                    parseJar(is, map, spring);
                } else if (key.matches(MANIFEST_MF)) {
                    System.out.println("ignore : " + key);
                } else if (anyMatchPattern(META_INF_IGNORE_LIST, key)) {
                    System.out.println("ignore : " + key);
                } else {
                    if (isDebug) {
                        System.out.println("classes : " + key);
                    }
                    if (map.containsKey(key)) {
                        if (key.endsWith(SPRING_FACTORIES)) {
                            Properties dataProperties = new Properties();
                            dataProperties.load(new ByteArrayInputStream(map.get(key)));
                            Properties properties = new Properties();
                            properties.load(new ByteArrayInputStream(data));
                            properties.forEach((k, v) -> {
                                String existing = dataProperties.getProperty(k.toString());
                                dataProperties.setProperty(k.toString(), existing != null ? existing + "," + v.toString() : v.toString());
                            });
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            dataProperties.store(os, "Merged by JarBoot");
                            data = os.toByteArray();
                        } else if (key.endsWith(SPRING_SCHEMAS) || key.endsWith(SPRING_HANDLERS)) {
                            byte[] data1 = map.get(key);
                            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                            byteStream.write(data1);
                            byteStream.write(10);
                            byteStream.write(data);
                            data = byteStream.toByteArray();
                        } else if (key.indexOf(SERVICES_PATH) > -1) {
                            byte[] data12 = map.get(key);
                            if (data12.length > 0) {
                                ByteArrayOutputStream byteStream2 = new ByteArrayOutputStream();
                                byteStream2.write(data12);
                                byteStream2.write(10);
                                byteStream2.write(data);
                                data = byteStream2.toByteArray();
                            }
                        }
                    }
                    map.put(key, data);
                }
            } else {
                return;
            }
        }
    }

    private static void writeClass(Map<String, byte[]> map, String jarDir) throws IOException {
        for (Map.Entry<String, byte[]> entry : map.entrySet()) {
            String key = entry.getKey();
            byte[] data = entry.getValue();
            if (key.endsWith("/")) {
                File f = new File(jarDir + key);
                if (!f.exists()) {
                    f.mkdirs();
                }
            } else {
                File f2 = new File(jarDir + key.substring(0, key.lastIndexOf("/")));
                if (!f2.exists()) {
                    f2.mkdirs();
                }
                if (key.contains(pkg) && key.endsWith(".class")) {
                    boolean ignoreEncryptClass = anyContainsStr(IGNORE_ENCRYPT_CLASS, key);
                    if (ignoreEncryptClass) {
                        System.out.println("writeClass no enc : " + key);
                    } else {
                        System.out.println("writeClass enc : " + key);
                        data = mjarencrypt.encrypt(data);
                    }
                }
                Files.write(new File(jarDir + key).toPath(), data);
            }
        }
    }

    private static byte[] patchClassReader(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, 0);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                // 找到 (byte[] classFileBuffer, int classFileOffset, boolean check) 构造
                if ("<init>".equals(name) && "([BIZ)V".equals(desc)) {
                    System.out.println("patched ClassReader." + name + desc);
                    return new MethodVisitor(api, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // 在方法最开始插入：
                            //   aload_1
                            //   iload_2
                            //   invokestatic NativeDecryptHelper.maybeDecrypt([BI)[B
                            //   astore_1
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitVarInsn(Opcodes.ILOAD, 2);
                            mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "com/github/jsbxyyx/mjar/NativeDecryptHelper",
                                    "maybeDecrypt",
                                    "([BI)[B",
                                    false);
                            mv.visitVarInsn(Opcodes.ASTORE, 1);
                        }
                    };
                }
                return mv;
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "/");
    }

    public static <T> List<T> newList(T... objects) {
        if (objects != null) {
            List<T> list = new ArrayList<>(objects.length);
            for (T object : objects) {
                list.add(object);
            }
            return list;
        }
        return new ArrayList<>();
    }

    public static <T> Set<T> newSet(T... objects) {
        if (objects != null) {
            Set<T> set = new HashSet<>(objects.length);
            for (T object : objects) {
                set.add(object);
            }
            return set;
        }
        return new HashSet<>();
    }

    public static boolean anyMatchPattern(List<String> patterns, String str) {
        if (patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (str.matches(pattern)) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyContainsStr(Collection<String> list, String str) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String s : list) {
            if (str.contains(s)) {
                return true;
            }
        }
        return false;
    }

    public static int copy(InputStream in, OutputStream out) throws IOException {
        if (in == null) {
            throw new NullPointerException("No InputStream specified");
        }
        if (out == null) {
            throw new NullPointerException("No OutputStream specified");
        }

        int byteCount = 0;
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            byteCount += bytesRead;
        }
        out.flush();
        return byteCount;
    }

}
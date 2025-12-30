# mjar-java

中文 [English](README_en.md)

`mjar-java` 是 [mjar](https://github.com/jsbxyyx/mjar) 的 Java 侧配套项目，主要用于对 JAR / WAR 中的 **class 文件进行加密与字节码修改**，并通过 `libmjar` 原生库完成实际加密。

> 本项目需要配合 [mjar](https://github.com/jsbxyyx/mjar) 一起使用。  
> 加密无法提供绝对安全，但可以显著提高静态分析和逆向工程的成本。

---

## 主要类与职责

包名统一为 `com.github.jsbxyyx.mjar`：

- `Mjarencrypt`  
  Java 封装的原生加密器：
  - 定义 `public native byte[] encrypt(byte[] bArr);`
  - 根据 `os.name` 判断当前系统：
    - Windows：`libmjar.dll`
    - macOS：`libmjar.dylib`
    - 其他：`libmjar.so`
  - 从以下路径加载原生库：
    - 系统属性 `LIB_MJAR_PATH`；如果未设置，
    - 使用 `user.dir`（当前工作目录）

- `Mjarencrypt2`  
  基于 ASM 的 JAR 转换工具，逻辑概括如下：
  1. 读取目标 JAR，解析 `Manifest`（`main-class` / `start-class`）
  2. 判断是否为 Spring Boot fat jar（根据 main class 包名）
  3. 遍历所有 Entry，将内容读入 `Map<String, byte[]>`
  4. 对指定包前缀下的 `.class` 使用 `Mjarencrypt.encrypt` 进行加密  
     - 某些类（加载器、补丁类、`META-INF` 下资源等）会被忽略 (`IGNORE_ENCRYPT_CLASS`)
  5. 将处理后的类写回临时目录
  6. 使用原始 `Manifest` 重新打包为 `*-enc.jar`
  7. 删除临时解压目录

- `Mjarencrypt3`  
  一个简单的命令行测试工具，用于测试原生加密接口：

  ```bash
  java -cp mjar.jar com.github.jsbxyyx.mjar.Mjarencrypt3 <string>
  ```

  会把 `<string>` 使用 UTF‑8 转成 byte 数组，加密后以 16 进制形式打印。

- `Mjarencrypt4`  
  新版递归式转换器，支持 JAR、WAR 及内部嵌套的 archive，具备：
  - 树形结构输出（标记哪些是 archive、哪些是 class、是否加密/打补丁）
  - 通过 ASM 根据规则对 class 进行加密或插桩
  - 最终生成 `-enc.jar` 或 `-enc.war` 文件

---

## 构建

典型 Maven 构建：

```bash
mvn clean package
```

或者 Gradle：

```bash
./gradlew build
```

构建产物通常位于 `target/` 或 `build/libs/` 目录（视构建脚本而定）。

---

## 命令行用法

### 1. 测试原生加密（Mjarencrypt3）

```bash
java -cp mjar.jar com.github.jsbxyyx.mjar.Mjarencrypt3 <string>
```

- `<string>`：任意要加密的字符串  
- 输出：加密后字节的 16 进制字符串

---

### 2. 加密 JAR（Mjarencrypt2）

在 `Mjarencrypt2.run` 中定义的用法：

```text
usage: java -jar mjar.jar com/github/jsbxyyx xx.jar [DEBUG]
```

对应参数：

- `args[0]`：**包前缀（内部路径形式）**，例如：
  - `com/github/jsbxyyx`
- `args[1]`：待加密的 JAR 文件路径，例如 `app.jar`
- `args[2]`：可选，`DEBUG`（大小写不敏感）启用调试输出

执行流程：

1. 输出参数信息：
   ```text
   args : [com/github/jsbxyyx, app.jar, DEBUG]
   ```
2. 读取目标 JAR 的 `Manifest`：
   - `main-class`
   - `start-class`
3. 判断是否为 Spring Boot 应用：
   - 通过 `main-class` 包名前缀是否为 `org.springframework.boot` 等
4. 调用 `parseJar`：
   - 遍历所有 `JarEntry`
   - 对 Spring Boot 的 `BOOT-INF/classes/`、`BOOT-INF/lib/` 做路径归一化
5. 对在目标包前缀下的 `.class` 执行加密（`Mjarencrypt.encrypt`）
6. 调用 `writeClass` 把处理后的类写入临时目录
7. 调用 `writeJar` 重新打包为 `*-enc.jar`
8. 调用 `clean` 递归删除临时目录

控制台示例输出（简化）：

```text
main-class : ...
start-class : ...
springboot : true/false
writeJar : /path/to/app-enc.jar
clean : /path/to/app
final filename : /path/to/app-enc.jar
```

---

### 3. 加密 JAR / WAR（Mjarencrypt4）

`Mjarencrypt4` 的入口用法在 `main` 中：

```bash
java -jar mjar.jar <pkg_prefix> <source_jar_or_war> [DEBUG]
```

参数说明：

- `pkg_prefix`：包前缀，**点分形式**，例如 `com.github.jsbxyyx`  
  程序内部会转换成 `com/github/jsbxyyx`
- `source_jar_or_war`：源 JAR 或 WAR 路径
- `DEBUG`（可选）：打开调试输出

输出文件名规则：

- 输入 `app.jar` → 输出 `app-enc.jar`
- 输入 `app.war` → 输出 `app-enc.war`

示例：

```bash
java -jar mjar.jar com.github.jsbxyyx app.jar
```

控制台会打印类似树状结构（`printTree`）：

```text
Processing: app.jar
/
/├── [A] BOOT-INF/lib/...
├── [C] com/github/jsbxyyx/service/MyService.class [E][P]
...
>>> Encryption Complete: /path/to/app-enc.jar
```

标记含义：

- `[A]`：archive（嵌套 JAR/WAR）
- `[C]`：class 文件
  - `[E]`：会被加密
  - `[P]`：会被打补丁（插入 `maybeDecrypt` 等逻辑）

---

## 实现细节（简要）

- 使用 [ASM](https://asm.ow2.io/)：
  - `ClassReader` / `ClassWriter` / `ClassVisitor` / `MethodVisitor`
  - 在指定 class 中插入 `maybeDecrypt([BI)[B` 方法调用或其它逻辑
- 加密逻辑：
  - 所有需要加密的字节数组交给 `Mjarencrypt.encrypt` 处理
  - `Mjarencrypt` 底层依赖 `libmjar` 原生库完成实际加密
- Spring Boot 特殊处理：
  - 解析 `BOOT-INF/classes/` 和 `BOOT-INF/lib/` 下的内容
  - 在重新打包时保留原始 `Manifest` 与结构

可重点参考：

- [`Mjarencrypt2.java`](src/main/java/com/github/jsbxyyx/mjar/Mjarencrypt2.java)
- [`Mjarencrypt4.java`](src/main/java/com/github/jsbxyyx/mjar/Mjarencrypt4.java)

---

## 使用注意

- `IGNORE_ENCRYPT_CLASS` 中列出的类不会被加密，以保证运行时解密与启动流程正常。
- 某些框架/库大量使用反射、运行时生成字节码时，可能需要：
  - 调整包前缀
  - 将特定 class 排除在加密之外
- 密钥管理请放在 native / 外部配置中，不要直接硬编码在 Java 源码或公开仓库中。

---

## 许可证

如仓库中存在 [LICENSE](LICENSE) 文件，请以该文件为准。

---

## 相关项目

- [mjar](https://github.com/jsbxyyx/mjar) —— 原生加密与运行时加载的主项目。

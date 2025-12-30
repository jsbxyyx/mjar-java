# mjar-java

[中文](README.md) English

`mjar-java` is the Java-side companion for [mjar](https://github.com/jsbxyyx/mjar).  
It provides tools and bytecode transformers to **encrypt classes inside JAR/WAR archives**, and a small Java bridge to the native encryptor.

> This project is intended to be used together with [mjar](https://github.com/jsbxyyx/mjar).  
> Encryption cannot provide absolute security, but it significantly increases the cost of static analysis and reverse engineering.

---

## Modules / Main Classes

All code is under package `com.github.jsbxyyx.mjar`:

- `Mjarencrypt`  
  Java wrapper around the native library:
  ```java
  public class Mjarencrypt {
      public native byte[] encrypt(byte[] bArr);

      static {
          String os = System.getProperty("os.name").toLowerCase();
          String suffix = os.contains("windows") ? ".dll" : (os.contains("mac") ? ".dylib" : ".so");
          System.load(System.getProperty("LIB_MJAR_PATH", System.getProperty("user.dir")) + "/libmjar" + suffix);
      }
  }
  ```
  It loads `libmjar.{dll|dylib|so}` from:
  - `LIB_MJAR_PATH` system property, or
  - current working directory (`user.dir`) by default.

- `Mjarencrypt2`  
  ASM-based JAR transformer. It:
  - Unpacks a JAR
  - Scans/rewrites classes
  - Encrypts target classes via `Mjarencrypt.encrypt(byte[])`
  - Writes an encrypted JAR (`*-enc.jar`)
  - Deletes the temporary unpack directory

- `Mjarencrypt3`  
  Small helper CLI to test the native encryptor:
  ```bash
  java -cp mjar.jar com.github.jsbxyyx.mjar.Mjarencrypt3 <string>
  ```
  It prints the encrypted bytes of `<string>` in hex.

- `Mjarencrypt4`  
  A newer, recursive archive transformer which:
  - Walks through nested archives (e.g. Spring Boot fat jars, WARs)
  - Prints a tree view of what is processed
  - Encrypts/patches specific classes using ASM
  - Writes an `-enc.jar` or `-enc.war` file

---

## Build

This is a standard Maven/Gradle Java project. For Maven:

```bash
mvn clean package
```

For Gradle:

```bash
./gradlew build
```

Artifacts will be produced under `target/` (Maven) or `build/libs/` (Gradle), depending on your build setup.

---

## CLI Usage

### 1. Encrypt a String (test native library)

```bash
java -cp mjar.jar com.github.jsbxyyx.mjar.Mjarencrypt3 <string>
```

- `<string>`: any UTF‑8 string to encrypt  
- Output is a hex string representing the encrypted bytes.

### 2. Encrypt a JAR (Mjarencrypt2)

`Mjarencrypt2` expects:

```bash
java -jar mjar.jar com/github/jsbxyyx xx.jar [DEBUG]
```

From the code (`Mjarencrypt2.run`):

- `args[0]` – package prefix to be encrypted, for example:
  - `com/github/jsbxyyx`
- `args[1]` – target JAR file, e.g. `app.jar`
- `args[2]` – optional, `DEBUG` (case-insensitive) to enable debug output

Flow:

1. Read manifest and detect if it’s a Spring Boot jar:
   - Check `main-class` / `start-class`
2. Parse the JAR with ASM, collect entries into a `Map<String, byte[]>`
3. Encrypt only classes under the given package prefix (`pkg`)  
   - Some classes (e.g. patch classes, loader) are skipped via `IGNORE_ENCRYPT_CLASS`
4. Write modified classes to a temp dir
5. Repack to `WORK_DIR/filename-enc.jar`
6. Clean up the temp directory

Console output example:

```text
usage: java -jar mjar.jar com/github/jsbxyyx xx.jar [DEBUG]
args : [com/github/jsbxyyx, app.jar, DEBUG]
main-class : ...
start-class : ...
springboot : true/false
writeJar : /path/to/app-enc.jar
clean : /path/to/app
final filename : /path/to/app-enc.jar
```

### 3. Encrypt a JAR / WAR (Mjarencrypt4)

`Mjarencrypt4` is a more generic transformer and prints a tree view:

```bash
java -jar mjar.jar <pkg_prefix> <source_jar_or_war> [DEBUG]
```

From `Mjarencrypt4.main`:

- `args[0]` – package prefix (dot form), e.g. `com.github.jsbxyyx`
  - will be converted to internal form `com/github/jsbxyyx`
- `args[1]` – source JAR/WAR path
- `args[2]` – optional `DEBUG` to enable verbose logging

Output file:

- For `.jar`: `<name>-enc.jar`
- For `.war`: `<name>-enc.war`

Example:

```bash
java -jar mjar.jar com.github.jsbxyyx app.jar
```

Console output (simplified):

```text
Usage: java -jar mjar.jar <pkg_prefix> <source_jar> [DEBUG]
Processing: app.jar
/
├── [A] BOOT-INF/lib/...
├── [C] com/github/jsbxyyx/service/MyService.class [E][P]
...
>>> Encryption Complete: /path/to/app-enc.jar
```

Legend (from `printTree`):

- `[A]` – archive entry (nested JAR/WAR)
- `[C]` – class file
  - `[E]` – will be encrypted
  - `[P]` – will be patched

---

## How It Works (High Level)

- Uses [ASM](https://asm.ow2.io/) (`ClassReader`, `ClassWriter`, `ClassVisitor`, `MethodVisitor`) to:
  - Inspect classes
  - Inject/patch methods (e.g. a `maybeDecrypt([BI)[B` hook)
- Delegates encryption of byte arrays to `Mjarencrypt`:
  - Loads `libmjar.{dll|dylib|so}` at startup
  - Calls the native `encrypt(byte[])` method
- For Spring Boot fat jars:
  - Strips `BOOT-INF/classes/` / `BOOT-INF/lib/` prefixes when parsing
  - Rebuilds the manifest and resulting jar structure

For details, see:

- [`Mjarencrypt2.java`](src/main/java/com/github/jsbxyyx/mjar/Mjarencrypt2.java)
- [`Mjarencrypt4.java`](src/main/java/com/github/jsbxyyx/mjar/Mjarencrypt4.java)

---

## Requirements

- Java 8+ (or as required by your environment)
- Native library `libmjar.{dll|dylib|so}` compiled for your platform
- [mjar](https://github.com/jsbxyyx/mjar), which provides the native side and runtime support

---

## Notes

- Classes matching `IGNORE_ENCRYPT_CLASS` (patterns under `META-INF`, some internal helper classes, etc.) are not encrypted.
- Method `maybeDecrypt([BI)[B` is used as a hook and is patched via ASM in specific classes.
- Be careful with:
  - Reflective loading
  - Frameworks that rely heavily on bytecode inspection  
  These might require excluding certain packages from encryption.

---

## License

See [LICENSE](LICENSE) for details (if present in the repository).

---

## Related Project

- [mjar](https://github.com/jsbxyyx/mjar) – Native side and runtime loader for encrypted classes.

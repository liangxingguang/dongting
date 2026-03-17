# JLink 裁剪

使用 `jlink` 为 Dongting 创建裁剪后的精简 JRE。

## 前提条件

- JDK 11 或更高版本（推荐 JDK 17+）
- JDK 中包含 `jdeps` 和 `jlink` 工具

## 使用方法

在 `dongting-dist` 目录下执行：

```sh
./bin/jlink-cut.sh
```

Windows 系统：
```powershell
.\bin\jlink-cut.ps1
```

脚本将执行：
1. 使用 `jdeps` 分析 JDK 模块依赖
2. 使用 `jlink` 创建精简 JRE

## 输出结果

裁剪后的 JRE 将创建在 `dongting-dist/jre/` 目录：

- **大小**：约 30-45 MB（Linux 下略大）
- **模块**：通常包含 `java.base`, `java.xml`, `jdk.unsupported`

启动脚本（`start-dongting.sh`, `benchmark.sh`, `dongting-admin.sh`）会自动检测并使用裁剪后的 JRE。

## 输出示例

```
Found 6 JAR files in /path/to/dongting-dist/lib
Detected jlink version: 21, using --compress=zip-6
Analyzing JDK module dependencies...
Required modules: java.base,java.xml,jdk.unsupported
Creating trimmed JRE at /path/to/dongting-dist/jre...
Successfully created trimmed JRE at /path/to/dongting-dist/jre
JRE size: 35M
```

## 注意事项

- 如果 `jre` 目录已存在，请先删除后再重新运行
- 裁剪后的 JRE 仅包含 Dongting 运行所需的模块

## 极致小体积（进阶）

当前 `dongting-dist` 依赖 Logback。如果想要极致小的包，需要自己动手略微修改 `dist` 模块源码，把 Logback 换成 Java 内置的 Logging（`java.util.logging`），这样可以省下两个 Logback 的 Jar 包和 `java.xml` 模块（数 MB）。

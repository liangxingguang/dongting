# JLink Cut

Use `jlink` to create a trimmed JRE for Dongting.

## Prerequisites

- JDK 11 or higher (JDK 17+ recommended)
- `jdeps` and `jlink` tools in JDK

## Usage

In `dongting-dist` directory, run:

```sh
./bin/jlink-cut.sh
```

On Windows:
```powershell
.\bin\jlink-cut.ps1
```

The script will:
1. Analyze JDK module dependencies using `jdeps`
2. Create a trimmed JRE using `jlink`

## Output

The trimmed JRE will be created at `dongting-dist/jre/`:

- **Size**: approximately 30-45 MB (slightly larger on Linux)
- **Modules**: typically includes `java.base`, `java.xml`, `jdk.unsupported`

The startup scripts (`start-dongting.sh`, `benchmark.sh`, `dongting-admin.sh`) will automatically use the trimmed JRE if it exists.

## Example Output

```
Found 6 JAR files in /path/to/dongting-dist/lib
Detected jlink version: 21, using --compress=zip-6
Analyzing JDK module dependencies...
Required modules: java.base,java.xml,jdk.unsupported
Creating trimmed JRE at /path/to/dongting-dist/jre...
Successfully created trimmed JRE at /path/to/dongting-dist/jre
JRE size: 35M
```

## Notes

- If the `jre` directory already exists, delete it first before re-running
- The trimmed JRE only contains the modules required by Dongting

## Smaller Size (Advanced)

The current `dongting-dist` depends on Logback. If you want an even smaller package, you can modify the source code of the `dist` module to replace Logback with Java's built-in logging (`java.util.logging`). This can save two Logback JAR files and the `java.xml` module (several MB).

```markdown
# ⚡ Graphon Build Tool

Graphon is a lightweight, fast, and generic build tool written in Groovy. Designed to run efficiently on Android (Termux) and any JVM environment (ARM64 & x86_64).

## ✨ Key Features

- **Blazing Fast**: Uses a daemon mode to keep the JVM warm. Subsequent builds run in **~0.08 seconds**.
- **Memory Efficient**: Optimized JVM flags (`SerialGC`, `Metaspace` limits) and Custom JRE (`jlink`) support reduce RAM usage to ~120MB.
- **Incremental Build**: Uses `xxHash` and timestamps to skip tasks if inputs haven't changed.
- **Parallel Execution**: Independent tasks run concurrently using `CompletableFuture`.
- **Project Scaffolding**: Initialize standard project structures instantly (`graphon init android`, `ios`, `flatpak`).
- **Built-in Android Toolchain**: Automatically downloads `aapt2` and `d8` for ARM64/Termux users (`graphon setup-android`).
- **Plugin System**: Extend Graphon by dropping `.groovy` files into the `plugins/` folder.
- **Custom Extension**: Uses `.graphon` as its build file extension.
- **Cross-Platform**: Automatically detects architecture (ARM64/x86_64) and falls back to system Java if Custom JRE is unavailable.

## 🚀 Installation

### Prerequisites
Ensure you have Java and Groovy installed.
*   **Termux**: `pkg install openjdk-17 groovy`
*   **Linux (Debian/Ubuntu)**: `sudo apt install default-jdk groovy`

### Setup
1. Clone this repository:
   ```bash
   git clone https://github.com/USERNAME/graphon.git
   cd graphon
   ```
2. Run the installer:
   ```bash
   ./install.sh
   ```
   *This will install Graphon to `~/.graphon/` and create a symlink in `~/.local/bin/`.*

## 🛠️ Usage

### Initialize a new project
```bash
mkdir my_app && cd my_app
graphon init android   # or: ios, flatpak
```

### Setup Android Toolchain (For Termux ARM64)
Automatically downloads pre-built `aapt2` and `d8`:
```bash
graphon setup-android
```

### Build the project
```bash
graphon compile
graphon package
```

### Daemon Management
Graphon uses a background daemon to achieve sub-second build times.
```bash
graphon daemon:start   # Start background daemon
graphon daemon:stop    # Stop daemon
graphon status         # Check daemon status
```

### CLI Options
```bash
graphon --version      # Show version
graphon --help         # Show help menu
```

## 📝 Example `build.graphon`

Graphon uses a clean and expressive DSL powered by Groovy.

```groovy
project = "MyAwesomeApp"
version = "1.0.0"

srcDir = "src"
buildDir = "build"

task('clean') {
    doLast { exec "rm -rf $buildDir" }
}

task('compile') {
    dependsOn 'clean'
    inputs "$srcDir/main.c"
    outputs "$buildDir/app.o"
    doLast {
        exec "gcc -c $srcDir/main.c -o $buildDir/app.o"
    }
}

task('link') {
    dependsOn 'compile'
    inputs "$buildDir/app.o"
    outputs "$buildDir/myapp"
    doLast {
        exec "gcc $buildDir/app.o -o $buildDir/myapp"
    }
}

defaultTasks 'link'
```

## 🧩 Plugin System

You can create reusable tasks by creating `.groovy` files inside the `plugins/` directory in your project.

**Example `plugins/my-plugin.groovy`:**
```groovy
task('hello') {
    doLast {
        exec "echo Hello from Graphon Plugin!"
    }
}
```

## 📂 Directory Structure

When you install Graphon, it creates a hidden directory in your home folder:

```
~/.graphon/
├── bin/
│   └── graphon         # Bash CLI wrapper
├── lib/
│   └── graphon.groovy  # Core engine
├── jre/                # (Optional) Custom JRE goes here
├── plugins/            # Global plugins
└── logs/               # Daemon logs
```

---

## Made with ❤️ for the Termux & JVM community.

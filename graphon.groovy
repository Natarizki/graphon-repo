// graphon.groovy
// Graphon Build Tool - v1.0.0 (Cross-Platform, Multi-Arch, Instant Daemon)

@Grab('net.openhft:zero-allocation-hashing:0.16')
import net.openhft.hashing.LongHashFunction

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.net.ServerSocket
import java.net.Socket

// --- LOGGER FOR COLORFUL OUTPUT ---
class Logger {
    static final String RESET = "\033[0m"
    static final String RED = "\033[0;31m"
    static final String GREEN = "\033[0;32m"
    static final String YELLOW = "\033[0;33m"
    static final String BLUE = "\033[0;34m"
    static final String CYAN = "\033[0;36m"
    static final String MAGENTA = "\033[0;35m"

    static void info(String msg) { println "${CYAN}[Graphon]${RESET} $msg" }
    static void success(String msg) { println "${GREEN}[Graphon]${RESET} $msg" }
    static void error(String msg) { System.err.println "${RED}[Graphon Error]${RESET} $msg" }
    static void task(String name) { println "\n${BLUE}> Task : $name${RESET}" }
    static void exec(String cmd) { println "  ${YELLOW}-> Exec:${RESET} $cmd" }
    static void skip(String name) { println "  ${YELLOW}-> Skip:${RESET} Task '$name' is up-to-date." }
    static void daemon(String msg) { println "${CYAN}[Graphon Daemon]${RESET} $msg" }
    static void scaffold(String msg) { println "${MAGENTA}[Init]${RESET} $msg" }
    static void download(String msg) { println "${MAGENTA}[Download]${RESET} $msg" }
}

// --- TASK MODEL ---
class GraphonTask {
    String name
    String description = ""
    List<String> dependencies = []
    List<String> inputs = []
    List<String> outputs = []
    Closure action = {}

    void description(String desc) { this.description = desc }
    void doLast(Closure action) { this.action = action }
    void dependsOn(String... dep) { this.dependencies.addAll(dep) }
    void inputs(String... inps) { this.inputs.addAll(inps) }
    void outputs(String... outs) { this.outputs.addAll(outs) }
}

// --- CACHE MANAGER WITH XXHASH ---
class CacheManager {
    File cacheFile = new File('.graphon_cache')
    Map<String, String> cache = [:]
    LongHashFunction xxHash = LongHashFunction.xx()

    CacheManager() {
        if (cacheFile.exists()) {
            cacheFile.splitEachLine('=') { parts -> cache[parts[0]] = parts[1] }
        }
    }

    String hashFiles(List<String> patterns) {
        if (patterns.isEmpty()) return null
        long combinedHash = 0
        patterns.each { path ->
            def file = new File(path)
            if (file.exists()) {
                combinedHash ^= xxHash.hashBytes(file.bytes)
            }
        }
        return Long.toHexString(combinedHash)
    }

    boolean isUpToDate(GraphonTask task) {
        if (task.inputs.isEmpty() || task.outputs.isEmpty()) return false
        boolean outputsExist = task.outputs.every { new File(it).exists() }
        if (!outputsExist) return false

        def maxInputTime = task.inputs.collect { new File(it).lastModified() }.max()
        def minOutputTime = task.outputs.collect { new File(it).lastModified() }.min()
        
        if (maxInputTime <= minOutputTime) return true 

        String currentHash = hashFiles(task.inputs)
        String cachedHash = cache[task.name]
        return currentHash != null && currentHash == cachedHash
    }

    void updateCache(GraphonTask task) {
        if (!task.inputs.isEmpty()) {
            cache[task.name] = hashFiles(task.inputs)
            save()
        }
    }

    private void save() {
        cacheFile.text = cache.collect { k, v -> "$k=$v" }.join('\n')
    }
}

// --- PROJECT INITIALIZER (SCAFFOLDING) ---
class ProjectInitializer {
    void init(String platform) {
        switch(platform.toLowerCase()) {
            case "android":
                initAndroid()
                break
            case "ios":
                initIOS()
                break
            case "flatpak":
                initFlatpak()
                break
            default:
                Logger.error("Unknown platform: $platform. Available: android, ios, flatpak")
                System.exit(1)
        }
    }

    private void createFile(String path, String content) {
        def file = new File(path)
        if (file.parentFile != null) {
            file.parentFile.mkdirs()
        }
        if (!file.exists()) {
            file.text = content
            Logger.scaffold("Created: $path")
        } else {
            Logger.scaffold("Skipped (exists): $path")
        }
    }

    private void initAndroid() {
        Logger.info("Initializing Standard Android Project Structure...")
        
        createFile("build.graphon", '''\
project = "MyAndroidApp"
version = "1.0.0"

srcDir = "src/main/java"
resDir = "src/main/res"
buildDir = "build"

task('clean') {
    doLast { exec "rm -rf $buildDir" }
}

task('compile') {
    dependsOn 'clean'
    
    def inputFiles = []
    new File(srcDir).eachFileRecurse { if (it.name.endsWith('.java')) inputFiles << it.path }
    new File(resDir).eachFileRecurse { if (it.name.endsWith('.xml')) inputFiles << it.path }
    
    inputs inputFiles
    outputs "$buildDir/classes"
    doLast {
        exec "mkdir -p $buildDir/classes"
        exec "ecj -d $buildDir/classes \\$(find $srcDir -name '*.java')"
    }
}

task('package') {
    dependsOn 'compile'
    inputs "$buildDir/classes"
    outputs "$buildDir/app.apk"
    doLast {
        exec "aapt2 compile --dir $resDir -o $buildDir/compiled_resources.zip"
        exec "aapt2 link -o $buildDir/app.unsigned.apk -I \\$ANDROID_HOME/platforms/android-*.jar --manifest src/main/AndroidManifest.xml -R $buildDir/compiled_resources.zip --auto-add-overlay"
        exec "d8 --output $buildDir $buildDir/classes/*.class"
        exec "cd $buildDir && zip -r app.apk classes.dex && cd .."
    }
}

defaultTasks 'package'
''')

        createFile("src/main/AndroidManifest.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myapp">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
''')

        createFile("src/main/res/values/strings.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">My Graphon App</string>
</resources>
''')

        createFile("src/main/res/layout/activity_main.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Hello from Graphon!"
        android:textSize="24sp" />

</LinearLayout>
''')

        createFile("src/main/java/com/example/myapp/MainActivity.java", '''\
package com.example.myapp;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
''')

        Logger.success("Standard Android project ready! Run: graphon package")
    }

    private void initIOS() {
        Logger.info("Initializing Standard iOS Project Structure...")
        
        createFile("build.graphon", '''\
project = "MyiOSApp"
version = "1.0.0"

srcDir = "src"
resDir = "resources"
buildDir = "build"

task('clean') {
    doLast { exec "rm -rf $buildDir" }
}

task('compile') {
    dependsOn 'clean'
    inputs "$srcDir/main.m"
    outputs "$buildDir/MyApp"
    doLast {
        exec "mkdir -p $buildDir"
        exec "clang -framework Foundation -framework UIKit $srcDir/main.m -o $buildDir/MyApp"
    }
}

task('package') {
    dependsOn 'compile'
    inputs "$buildDir/MyApp"
    inputs "$resDir/Info.plist"
    outputs "$buildDir/MyApp.app"
    doLast {
        exec "mkdir -p $buildDir/MyApp.app"
        exec "cp $buildDir/MyApp $buildDir/MyApp.app/"
        exec "cp $resDir/Info.plist $buildDir/MyApp.app/"
    }
}

defaultTasks 'package'
''')

        createFile("resources/Info.plist", '''\
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>MyApp</string>
    <key>CFBundleIdentifier</key>
    <string>com.example.myapp</string>
    <key>CFBundleVersion</key>
    <string>1.0.0</string>
    <key>CFBundleExecutable</key>
    <string>MyApp</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSRequiresIPhoneOS</key>
    <true/>
</dict>
</plist>
''')

        createFile("src/main.m", '''\
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

@interface AppDelegate : UIResponder <UIApplicationDelegate>
@property (strong, nonatomic) UIWindow *window;
@end

@implementation AppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:[[UIScreen mainScreen] bounds]];
    self.window.backgroundColor = [UIColor whiteColor];
    [self.window makeKeyAndVisible];
    NSLog(@"Hello from Graphon iOS!");
    return YES;
}
@end

int main(int argc, char * argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass([AppDelegate class]));
    }
}
''')

        Logger.success("Standard iOS project ready! Run: graphon package")
    }

    private void initFlatpak() {
        Logger.info("Initializing Standard Flatpak Project Structure...")
        
        createFile("build.graphon", '''\
project = "MyFlatpakApp"
version = "1.0.0"

srcDir = "src"
buildDir = "build"
appName = "myapp"

task('clean') {
    doLast { exec "rm -rf $buildDir" }
}

task('compile') {
    dependsOn 'clean'
    inputs "$srcDir/main.c"
    outputs "$buildDir/$appName"
    doLast {
        exec "mkdir -p $buildDir"
        exec "gcc $srcDir/main.c -o $buildDir/$appName"
    }
}

task('bundle') {
    dependsOn 'compile'
    inputs "com.example.MyApp.json"
    outputs "$buildDir/repo"
    doLast {
        exec "flatpak-builder --user --install --force-clean $buildDir/repo com.example.MyApp.json"
    }
}

defaultTasks 'bundle'
''')

        createFile("com.example.MyApp.json", '''\
{
    "app-id": "com.example.MyApp",
    "runtime": "org.freedesktop.Platform",
    "runtime-version": "23.08",
    "sdk": "org.freedesktop.Sdk",
    "command": "myapp",
    "finish-args": [
        "--share=network",
        "--socket=fallback-x11",
        "--socket=wayland",
        "--device=dri"
    ],
    "modules": [
        {
            "name": "myapp",
            "buildsystem": "simple",
            "build-commands": [
                "install -D myapp /app/bin/myapp"
            ],
            "sources": [
                {
                    "type": "file",
                    "path": "build/myapp"
                }
            ]
        }
    ]
}
''')

        createFile("src/main.c", '''\
#include <stdio.h>

int main(int argc, char *argv[]) {
    printf("Hello from Graphon Flatpak!\\\\n");
    return 0;
}
''')

        createFile("resources/com.example.MyApp.desktop", '''\
[Desktop Entry]
Type=Application
Name=My Graphon App
Exec=myapp
Icon=applications-development
Terminal=true
Categories=Development;
''')

        Logger.success("Standard Flatpak project ready! Run: graphon bundle")
    }
}

// --- ANDROID TOOLCHAIN SETUP (MULTI-ARCHITECTURE SUPPORT) ---
class AndroidToolchainSetup {
    void setup() {
        def binDir = new File(System.getProperty('user.home'), '.local/bin')
        binDir.mkdirs()
        
        def osArch = System.getProperty('os.arch')
        
        if (osArch == "aarch64" || osArch == "arm64") {
            Logger.info("Detected ARM64 architecture. Downloading pre-built binaries...")
            def aapt2Url = "https://github.com/rendiix/termux-aapt/releases/download/v1.0/aapt2"
            def d8Url = "https://github.com/rendiix/termux-aapt/releases/download/v1.0/d8"
            
            downloadFile("aapt2", aapt2Url, binDir)
            downloadFile("d8", d8Url, binDir)
            
            Logger.success("Android toolchain (aapt2, d8) is ready in ~/.local/bin")
            Logger.info("Pastikan ~/.local/bin ada di PATH kamu.")
            
        } else if (osArch == "amd64" || osArch == "x86_64") {
            Logger.info("Detected x86_64 architecture.")
            Logger.info("Checking for existing aapt2 and d8 in system PATH...")
            
            def aapt2Check = ["sh", "-c", "command -v aapt2"].execute()
            aapt2Check.waitFor()
            def d8Check = ["sh", "-c", "command -v d8"].execute()
            d8Check.waitFor()
            
            if (aapt2Check.exitValue() == 0 && d8Check.exitValue() == 0) {
                Logger.success("aapt2 and d8 are already installed in the system.")
            } else {
                Logger.error("aapt2 or d8 not found in PATH.")
                Logger.info("For x86_64, please install Android SDK or build tools manually.")
                Logger.info("Usually you can get them via 'sdkmanager \"build-tools;33.0.0\"'")
            }
        } else {
            Logger.error("Unsupported architecture: $osArch")
        }
    }
    
    private void downloadFile(String name, String url, File dir) {
        def file = new File(dir, name)
        if (file.exists()) {
            Logger.download("$name already exists. Skipping...")
            return
        }
        
        Logger.download("Downloading $name from $url...")
        try {
            def process = ["curl", "-L", "-o", file.absolutePath, url].execute()
            process.waitFor()
            
            if (process.exitValue() == 0 && file.exists() && file.length() > 0) {
                file.setExecutable(true, false)
                Logger.download("$name downloaded and made executable.")
            } else {
                Logger.error("Failed to download $name using curl.")
                file.delete()
            }
        } catch (Exception e) {
            Logger.error("Failed to download $name: ${e.message}")
            Logger.error("Please download manually and place in ~/.local/bin")
        }
    }
}

// --- GRAPHON ENGINE ---
class Graphon {
    Map<String, GraphonTask> tasks = [:]
    List<String> defaultTasks = []
    CacheManager cache = new CacheManager()
    Binding projectBinding
    
    List<String> order = []
    Set<String> visited = []
    Set<String> visiting = []

    void task(String name, Closure config) {
        GraphonTask task = new GraphonTask(name: name)
        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        tasks[name] = task
    }

    void exec(String command) {
        Logger.exec(command)
        try {
            def process = ["sh", "-c", command].execute()
            process.waitFor()
            process.in.eachLine { println "     $it" }
            process.err.eachLine { System.err.println "     [ERR] $it" }
            if (process.exitValue() != 0) {
                throw new RuntimeException("Command failed with exit code ${process.exitValue()}")
            }
        } catch (IOException e) {
            Logger.error("Command not found or failed: ${e.message}")
        }
    }

    private void visit(String taskName) {
        if (!tasks.containsKey(taskName)) throw new RuntimeException("Error: Task '$taskName' not found!")
        if (visited.contains(taskName)) return
        if (visiting.contains(taskName)) throw new RuntimeException("Circular dependency detected for task: $taskName")
        
        visiting << taskName
        tasks[taskName].dependencies.each { dep -> visit(dep) }
        visiting.remove(taskName)
        
        order << taskName
        visited << taskName
    }

    private List<String> resolveTaskOrder(List<String> requestedTasks) {
        order = []
        visited = []
        visiting = []
        requestedTasks.each { visit(it) }
        return order
    }

    void loadPlugins() {
        File pluginsDir = new File('plugins')
        if (!pluginsDir.exists()) return
        
        pluginsDir.eachFileMatch(groovy.io.FileType.FILES, ~/.*\.groovy/) { pluginFile ->
            Logger.info("Loading plugin: ${pluginFile.name}")
            GroovyShell shell = new GroovyShell()
            Script pluginScript = shell.parse(pluginFile)
            
            pluginScript.binding.task = { String n, Closure c -> this.task(n, c) }
            pluginScript.binding.exec = { String cmd -> this.exec(cmd) }
            pluginScript.binding.defaultTasks = { String... t -> this.defaultTasks = t.toList() }
            
            if (this.projectBinding != null) {
                this.projectBinding.variables.each { k, v ->
                    if (!pluginScript.binding.hasVariable(k)) {
                        pluginScript.binding.setVariable(k, v)
                    }
                }
            }
            pluginScript.run()
        }
    }

    void loadConfiguration() {
        File buildFile = new File('build.graphon')
        if (!buildFile.exists()) {
            buildFile = new File('build.groovy')
        }
        
        if (!buildFile.exists()) {
            throw new RuntimeException("build.graphon not found!")
        }

        Logger.info("Loading ${buildFile.name}...")
        GroovyShell shell = new GroovyShell()
        Script script = shell.parse(buildFile)
        
        this.projectBinding = script.binding
        
        script.binding.task = { String n, Closure c -> this.task(n, c) }
        script.binding.exec = { String cmd -> this.exec(cmd) }
        script.binding.defaultTasks = { String... t -> this.defaultTasks = t.toList() }
        
        script.run()
        loadPlugins()
    }

    void executeTasks(List<String> tasksToRun) {
        List<String> executionOrder = this.resolveTaskOrder(tasksToRun)
        Logger.info("Execution order -> ${executionOrder}")
        
        def executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        def futures = [:]
        
        executionOrder.each { taskName ->
            def task = tasks[taskName]
            def depFutures = task.dependencies.collect { futures[it] }
            
            futures[taskName] = CompletableFuture.runAsync({
                CompletableFuture.allOf(*depFutures.toArray()).join()
                runTask(taskName)
            }, executor)
        }
        
        CompletableFuture.allOf(*futures.values().toArray()).join()
        executor.shutdown()
        
        Logger.success("BUILD SUCCESSFUL")
    }

    void run(String[] args) {
        if (args.length > 0) {
            switch(args[0]) {
                case "--version":
                case "-v":
                    println "Graphon v1.0.0"
                    return
                case "--help":
                case "-h":
                    println "Graphon Build Tool v1.0.0"
                    println "Usage: graphon [task] [options]"
                    println ""
                    println "Commands:"
                    println "  init <android|ios|flatpak>  Initialize a new project structure."
                    println "  setup-android               Download built-in aapt2 & d8 for ARM64 (Termux)."
                    println "  daemon:start                Start background daemon."
                    println "  daemon:stop                 Stop background daemon."
                    println "  status                      Check daemon status."
                    println ""
                    println "Options:"
                    println "  --version, -v               Show version info."
                    println "  --help, -h                  Show this help message."
                    return
                case "setup-android":
                    new AndroidToolchainSetup().setup()
                    return
                case "init":
                    if (args.length < 2) {
                        Logger.error("Usage: graphon init <android|ios|flatpak>")
                        System.exit(1)
                    }
                    new ProjectInitializer().init(args[1])
                    return
            }
        }
        
        loadConfiguration()
        List<String> tasksToRun = args.length > 0 ? args.toList() : this.defaultTasks
        executeTasks(tasksToRun)
    }

    private void runTask(String taskName) {
        def task = tasks[taskName]
        if (cache.isUpToDate(task)) {
            Logger.skip(taskName)
            return
        }
        
        Logger.task(taskName)
        task.action.delegate = this
        task.action.resolveStrategy = Closure.DELEGATE_FIRST
        task.action.call()
        
        cache.updateCache(task)
    }
}

// --- DAEMON MODE OPTIMIZED (WORKSPACE-AWARE & INSTANT) ---
class GraphonDaemon {
    static final int PORT = 9876
    Graphon engine = new Graphon()
    boolean running = true
    
    void start() {
        Logger.daemon("Starting on port $PORT...")
        
        File buildFile = new File('build.graphon')
        if (!buildFile.exists()) {
            buildFile = new File('build.groovy')
        }
        
        if (!buildFile.exists()) {
            Logger.error("build.graphon not found in startup directory!")
            System.exit(1)
        }
        
        loadBuildFile()
        
        ServerSocket server = new ServerSocket(PORT)
        Logger.success("Daemon ready. Waiting for commands...")
        
        while (running) {
            try {
                Socket client = server.accept()
                handleClient(client)
            } catch (Exception e) {
                Logger.error("Daemon error: ${e.message}")
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            def reader = new BufferedReader(new InputStreamReader(client.inputStream))
            def writer = new PrintWriter(client.outputStream, true)
            
            String rawInput = reader.readLine()
            
            if (rawInput == null || rawInput.trim().isEmpty()) {
                client.close()
                return
            }
            
            String cwd = ""
            String command = ""
            int sepIdx = rawInput.indexOf(' task:')
            if (rawInput.startsWith("cwd:") && sepIdx > 0) {
                cwd = rawInput.substring(4, sepIdx)
                command = rawInput.substring(sepIdx + 6).trim()
            } else {
                command = rawInput.trim()
            }
            
            if (command == "shutdown") {
                writer.println("Shutting down daemon...")
                running = false
            } else {
                String currentDir = System.getProperty('user.dir')
                if (cwd && cwd != currentDir) {
                    Logger.info("Working directory changed to $cwd. Reloading...")
                    System.setProperty('user.dir', cwd)
                    loadBuildFile()
                }

                if (command == "reload") {
                    loadBuildFile()
                    writer.println("Reloaded successfully.")
                } else {
                    List<String> tasksToRun = command.trim() ? command.split(' ').toList() : engine.defaultTasks
                    
                    def baos = new ByteArrayOutputStream()
                    def ps = new PrintStream(baos)
                    def originalOut = System.out
                    def originalErr = System.err
                    
                    System.out = ps
                    System.err = ps
                    
                    try {
                        engine.executeTasks(tasksToRun)
                        writer.println("BUILD SUCCESSFUL")
                    } catch (Exception e) {
                        writer.println("BUILD FAILED: ${e.message}")
                    }
                    
                    System.out = originalOut
                    System.err = originalErr
                    ps.flush()
                    
                    writer.println(baos.toString())
                }
            }
            
            writer.println("END_OF_OUTPUT")
            client.close()
        } catch (Exception e) {
            Logger.error("Client handling error: ${e.message}")
        }
    }
}

// --- CLI WRAPPER LOGIC ---
if (args.length > 0 && args[0] == "--daemon") {
    new GraphonDaemon().start()
} else {
    new Graphon().run(args)
}

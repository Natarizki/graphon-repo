// graphon.groovy
// Graphon Build Tool - v1.1.1 (Fix: Initial Load on Daemon Start)

@Grab('net.openhft:zero-allocation-hashing:0.16')
import net.openhft.hashing.LongHashFunction

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.net.UnixDomainSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

// --- LOGGER ---
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
    static void daemon(String msg) { println "${CYAN}[Daemon]${RESET} $msg" }
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

// --- CACHE MANAGER ---
class CacheManager {
    File cacheFile = new File('.graphon_cache')
    Map<String, String> cache = [:]
    LongHashFunction xxHash = LongHashFunction.xx()

    CacheManager() { if (cacheFile.exists()) cacheFile.splitEachLine('=') { parts -> cache[parts[0]] = parts[1] } }
    String hashFiles(List<String> patterns) {
        if (patterns.isEmpty()) return null
        long combinedHash = 0
        patterns.each { path -> def file = new File(path); if (file.exists()) combinedHash ^= xxHash.hashBytes(file.bytes) }
        return Long.toHexString(combinedHash)
    }
    boolean isUpToDate(GraphonTask task) {
        if (task.inputs.isEmpty() || task.outputs.isEmpty()) return false
        boolean outputsExist = task.outputs.every { new File(it).exists() }
        if (!outputsExist) return false
        def maxInputTime = task.inputs.collect { new File(it).lastModified() }.max()
        def minOutputTime = task.outputs.collect { new File(it).lastModified() }.min()
        if (maxInputTime <= minOutputTime) return true 
        return hashFiles(task.inputs) == cache[task.name]
    }
    void updateCache(GraphonTask task) { if (!task.inputs.isEmpty()) { cache[task.name] = hashFiles(task.inputs); save() } }
    private void save() { cacheFile.text = cache.collect { k, v -> "$k=$v" }.join('\n') }
}

// --- PROJECT INITIALIZER ---
class ProjectInitializer {
    void init(String platform) {
        switch(platform.toLowerCase()) {
            case "android": initAndroid(); break
            case "ios": initIOS(); break
            case "flatpak": initFlatpak(); break
            default: Logger.error("Unknown platform: $platform"); System.exit(1)
        }
    }
    private void createFile(String path, String content) {
        def file = new File(path)
        if (file.parentFile != null) file.parentFile.mkdirs()
        if (!file.exists()) { file.text = content; Logger.scaffold("Created: $path") } else { Logger.scaffold("Skipped: $path") }
    }
    private void initAndroid() {
        Logger.info("Initializing Standard Android Project Structure...")
        createFile("build.graphon", '''\
project = "MyAndroidApp"
version = "1.0.0"
srcDir = "src/main/java"
resDir = "src/main/res"
buildDir = "build"

task('clean') { doLast { exec "rm -rf $buildDir" } }
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
        createFile("src/main/AndroidManifest.xml", '<manifest package="com.example.myapp"><application><activity android:name=".MainActivity"/></application></manifest>')
        createFile("src/main/java/com/example/myapp/MainActivity.java", 'package com.example.myapp; public class MainActivity {}')
        Logger.success("Android project ready!")
    }
    private void initIOS() { Logger.info("iOS init placeholder") }
    private void initFlatpak() { Logger.info("Flatpak init placeholder") }
}

// --- ANDROID TOOLCHAIN ---
class AndroidToolchainSetup {
    void setup() {
        def binDir = new File(System.getProperty('user.home'), '.local/bin')
        binDir.mkdirs()
        def osArch = System.getProperty('os.arch')
        if (osArch == "aarch64" || osArch == "arm64") {
            Logger.info("Detected ARM64. Downloading...")
            downloadFile("aapt2", "https://github.com/rendiix/termux-aapt/releases/download/v1.0/aapt2", binDir)
            downloadFile("d8", "https://github.com/rendiix/termux-aapt/releases/download/v1.0/d8", binDir)
            Logger.success("Done.")
        } else { Logger.info("x86_64 detected. Assuming SDK is installed.") }
    }
    private void downloadFile(String name, String url, File dir) {
        def file = new File(dir, name)
        if (file.exists()) return
        Logger.download("Downloading $name...")
        def process = ["curl", "-L", "-o", file.absolutePath, url].execute()
        process.waitFor()
        if (process.exitValue() == 0) file.setExecutable(true, false)
    }
}

// --- GRAPHON ENGINE ---
class Graphon {
    Map<String, GraphonTask> tasks = [:]
    List<String> defaultTasks = []
    CacheManager cache = new CacheManager()
    Binding projectBinding
    List<String> order = []; Set<String> visited = []; Set<String> visiting = []

    void task(String name, Closure config) {
        GraphonTask task = new GraphonTask(name: name)
        config.delegate = task; config.resolveStrategy = Closure.DELEGATE_FIRST; config.call()
        tasks[name] = task
    }
    void exec(String command) {
        Logger.exec(command)
        try {
            def process = ["sh", "-c", command].execute(); process.waitFor()
            process.in.eachLine { println "     $it" }; process.err.eachLine { System.err.println "     [ERR] $it" }
            if (process.exitValue() != 0) throw new RuntimeException("Command failed")
        } catch (IOException e) { Logger.error("Failed: ${e.message}") }
    }
    private void visit(String taskName) {
        if (!tasks.containsKey(taskName)) throw new RuntimeException("Task '$taskName' not found!")
        if (visited.contains(taskName)) return
        if (visiting.contains(taskName)) throw new RuntimeException("Circular dependency: $taskName")
        visiting << taskName; tasks[taskName].dependencies.each { visit(it) }; visiting.remove(taskName)
        order << taskName; visited << taskName
    }
    private List<String> resolveTaskOrder(List<String> requestedTasks) {
        order = []; visited = []; visiting = []; requestedTasks.each { visit(it) }; return order
    }
    
    void loadConfiguration() {
        File buildFile = new File('build.graphon')
        if (!buildFile.exists()) buildFile = new File('build.groovy')
        if (!buildFile.exists()) throw new RuntimeException("build.graphon not found!")

        Logger.info("Loading ${buildFile.name}...")
        def isolatedLoader = new GroovyClassLoader(this.class.classLoader)
        def shell = new GroovyShell(isolatedLoader)
        Script script = shell.parse(buildFile)
        
        this.projectBinding = script.binding
        script.binding.task = { String n, Closure c -> this.task(n, c) }
        script.binding.exec = { String cmd -> this.exec(cmd) }
        script.binding.defaultTasks = { String... t -> this.defaultTasks = t.toList() }
        script.run()
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
                case "--version": println "Graphon v1.1.1"; return
                case "--help": println "Graphon Build Tool v1.1.1"; return
                case "setup-android": new AndroidToolchainSetup().setup(); return
                case "init":
                    if (args.length < 2) { Logger.error("Usage: init <android|ios|flatpak>"); return }
                    new ProjectInitializer().init(args[1]); return
            }
        }
        loadConfiguration()
        executeTasks(args.length > 0 ? args.toList() : this.defaultTasks)
    }
    private void runTask(String taskName) {
        def task = tasks[taskName]
        if (cache.isUpToDate(task)) { Logger.skip(taskName); return }
        Logger.task(taskName)
        task.action.delegate = this; task.action.resolveStrategy = Closure.DELEGATE_FIRST; task.action.call()
        cache.updateCache(task)
    }
}

// --- DAEMON MODE: UNIX DOMAIN SOCKET & CLASSLOADER ISOLATION ---
class GraphonDaemon {
    Graphon engine = new Graphon()
    boolean running = true
    
    void start() {
        def socketFile = new File(System.getProperty('user.home'), '.graphon/daemon.sock')
        if (socketFile.exists()) socketFile.delete()
        
        Logger.daemon("Starting Unix Domain Socket server at $socketFile...")
        
        // PERBAIKAN: Load initial configuration saat daemon pertama kali start!
        try {
            engine.loadConfiguration()
        } catch (Exception e) {
            Logger.error("Failed to load build.graphon on startup: ${e.message}")
        }
        
        def addr = UnixDomainSocketAddress.of(socketFile.path)
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(addr)
        
        socketFile.setReadable(true, true)
        socketFile.setWritable(true, true)
        socketFile.setExecutable(false, false)
        
        Logger.success("Daemon ready. Waiting for commands...")
        
        while (running) {
            try {
                SocketChannel client = server.accept()
                handleClient(client)
            } catch (Exception e) {
                Logger.error("Daemon error: ${e.message}")
            }
        }
    }
    
    private void handleClient(SocketChannel client) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8192)
            int bytesRead = client.read(buffer)
            if (bytesRead == -1) { client.close(); return }
            
            byte[] data = new byte[bytesRead]
            buffer.flip()
            buffer.get(data)
            String rawInput = new String(data, StandardCharsets.UTF_8).trim()
            
            if (rawInput.isEmpty()) { client.close(); return }
            
            String cwd = ""; String command = ""
            int sepIdx = rawInput.indexOf(' task:')
            if (rawInput.startsWith("cwd:") && sepIdx > 0) {
                cwd = rawInput.substring(4, sepIdx)
                command = rawInput.substring(sepIdx + 6).trim()
            } else { command = rawInput }
            
            String response = ""
            
            if (command == "shutdown") {
                response = "Shutting down daemon..."
                running = false
            } else {
                String currentDir = System.getProperty('user.dir')
                if (cwd && cwd != currentDir) {
                    System.setProperty('user.dir', cwd)
                    engine.tasks.clear()
                    engine.defaultTasks.clear()
                    engine.loadConfiguration()
                }
                if (command == "reload") {
                    engine.tasks.clear()
                    engine.defaultTasks.clear()
                    engine.loadConfiguration()
                    response = "Reloaded successfully."
                } else {
                    List<String> tasksToRun = command.trim() ? command.split(' ').toList() : engine.defaultTasks
                    def baos = new ByteArrayOutputStream()
                    def ps = new PrintStream(baos)
                    def originalOut = System.out; def originalErr = System.err
                    System.out = ps; System.err = ps
                    try {
                        engine.executeTasks(tasksToRun)
                        response = "BUILD SUCCESSFUL\n" + baos.toString()
                    } catch (Exception e) {
                        response = "BUILD FAILED: ${e.message}\n" + baos.toString()
                    }
                    System.out = originalOut; System.err = originalErr; ps.flush()
                }
            }
            
            byte[] responseData = (response + "\nEND_OF_OUTPUT\n").getBytes(StandardCharsets.UTF_8)
            client.write(ByteBuffer.wrap(responseData))
            client.close()
        } catch (Exception e) {
            Logger.error("Client error: ${e.message}")
        }
    }
}

// --- CLI WRAPPER LOGIC ---
if (args.length > 0 && args[0] == "--daemon") {
    new GraphonDaemon().start()
} else {
    new Graphon().run(args)
}

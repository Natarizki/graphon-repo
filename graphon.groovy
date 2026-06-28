// graphon.groovy
// Graphon Build Tool - All-in-One Edition
// Features: Colorful Output, Incremental Build, Parallel Execution, Plugin System, Daemon Mode

import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    static void info(String msg) { println "${CYAN}[Graphon]${RESET} $msg" }
    static void success(String msg) { println "${GREEN}[Graphon]${RESET} $msg" }
    static void error(String msg) { System.err.println "${RED}[Graphon Error]${RESET} $msg" }
    static void task(String name) { println "\n${BLUE}> Task : $name${RESET}" }
    static void exec(String cmd) { println "  ${YELLOW}-> Exec:${RESET} $cmd" }
    static void skip(String name) { println "  ${YELLOW}-> Skip:${RESET} Task '$name' is up-to-date." }
    static void daemon(String msg) { println "${CYAN}[Graphon Daemon]${RESET} $msg" }
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

// --- CACHE MANAGER FOR INCREMENTAL BUILD ---
class CacheManager {
    File cacheFile = new File('.graphon_cache')
    Map<String, String> cache = [:]

    CacheManager() {
        if (cacheFile.exists()) {
            cacheFile.splitEachLine('=') { parts -> cache[parts[0]] = parts[1] }
        }
    }

    String hashFiles(List<String> patterns) {
        if (patterns.isEmpty()) return null
        def md = MessageDigest.getInstance("SHA-256")
        
        patterns.each { path ->
            def file = new File(path)
            if (file.exists()) {
                file.withInputStream { is -> md.update(is.bytes) }
            }
        }
        return md.digest().encodeHex().toString()
    }

    boolean isUpToDate(GraphonTask task) {
        if (task.inputs.isEmpty() || task.outputs.isEmpty()) return false
        
        // Check if outputs exist
        boolean outputsExist = task.outputs.every { new File(it).exists() }
        if (!outputsExist) return false

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

// --- GRAPHON ENGINE ---
class Graphon {
    Map<String, GraphonTask> tasks = [:]
    List<String> defaultTasks = []
    CacheManager cache = new CacheManager()
    Binding projectBinding // Store binding from build.groovy
    
    // State for Topological Sort
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
        if (!tasks.containsKey(taskName)) {
            throw new RuntimeException("Error: Task '$taskName' not found!")
        }
        if (visited.contains(taskName)) return
        if (visiting.contains(taskName)) {
            throw new RuntimeException("Circular dependency detected for task: $taskName")
        }
        
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

    // --- PLUGIN SYSTEM ---
    void loadPlugins() {
        File pluginsDir = new File('plugins')
        if (!pluginsDir.exists()) return
        
        pluginsDir.eachFileMatch(groovy.io.FileType.FILES, ~/.*\.groovy/) { pluginFile ->
            Logger.info("Loading plugin: ${pluginFile.name}")
            GroovyShell shell = new GroovyShell()
            Script pluginScript = shell.parse(pluginFile)
            
            // Inject same bindings as build.groovy
            pluginScript.binding.task = { String n, Closure c -> this.task(n, c) }
            pluginScript.binding.exec = { String cmd -> this.exec(cmd) }
            pluginScript.binding.defaultTasks = { String... t -> this.defaultTasks = t.toList() }
            
            // Copy variables from build.groovy binding
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

    void run(String[] args) {
        File buildFile = new File('build.groovy')
        if (!buildFile.exists()) {
            Logger.error("build.groovy not found!")
            System.exit(1)
        }

        Logger.info("Loading build.groovy...")
        GroovyShell shell = new GroovyShell()
        Script script = shell.parse(buildFile)
        
        // Save binding for plugins
        this.projectBinding = script.binding
        
        script.binding.task = { String n, Closure c -> this.task(n, c) }
        script.binding.exec = { String cmd -> this.exec(cmd) }
        script.binding.defaultTasks = { String... t -> this.defaultTasks = t.toList() }
        
        script.run()
        
        // Load plugins after build.groovy
        loadPlugins()

        List<String> tasksToRun = args.length > 0 ? args.toList() : this.defaultTasks
        List<String> executionOrder = this.resolveTaskOrder(tasksToRun)
        
        Logger.info("Execution order -> ${executionOrder}")
        
        // Parallel Execution using Thread Pool
        def executor = Executors.newFixedThreadPool(4) // 4 parallel threads
        def pendingTasks = new java.util.concurrent.ConcurrentLinkedQueue<String>(executionOrder)
        def completedTasks = Collections.synchronizedSet(new HashSet<String>())
        
        while (!pendingTasks.isEmpty()) {
            // Find tasks whose dependencies are all completed
            def readyTasks = pendingTasks.findAll { taskName ->
                tasks[taskName].dependencies.every { it in completedTasks }
            }
            
            if (readyTasks.isEmpty()) {
                // Should not happen if DAG is correct, but safety break
                Thread.sleep(100)
                continue
            }
            
            readyTasks.each { taskName ->
                pendingTasks.remove(taskName)
                executor.submit {
                    try {
                        runTask(taskName)
                        completedTasks.add(taskName)
                    } catch (Exception e) {
                        Logger.error(e.message)
                        executor.shutdownNow()
                    }
                }
            }
        }
        
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.HOURS)
        
        Logger.success("BUILD SUCCESSFUL")
    }

    private void runTask(String taskName) {
        def task = tasks[taskName]
        
        // Incremental Build Check
        if (cache.isUpToDate(task)) {
            Logger.skip(taskName)
            return
        }
        
        Logger.task(taskName)
        task.action.delegate = this
        task.action.resolveStrategy = Closure.DELEGATE_FIRST
        task.action.call()
        
        // Update cache after successful execution
        cache.updateCache(task)
    }
}

// --- DAEMON MODE ---
class GraphonDaemon {
    static final int PORT = 9876
    Graphon engine = new Graphon()
    boolean running = true
    
    void start() {
        Logger.daemon("Starting on port $PORT...")
        
        // Preload build.groovy
        File buildFile = new File('build.groovy')
        if (!buildFile.exists()) {
            Logger.error("build.groovy not found!")
            System.exit(1)
        }
        
        loadBuildFile()
        engine.loadPlugins()
        
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
    
    private void loadBuildFile() {
        GroovyShell shell = new GroovyShell()
        Script script = shell.parse(new File('build.groovy'))
        
        // Save binding on engine
        engine.projectBinding = script.binding
        
        script.binding.task = { String n, Closure c -> engine.task(n, c) }
        script.binding.exec = { String cmd -> engine.exec(cmd) }
        script.binding.defaultTasks = { String... t -> engine.defaultTasks = t.toList() }
        
        script.run()
    }
    
    private void handleClient(Socket client) {
        try {
            def reader = new BufferedReader(new InputStreamReader(client.inputStream))
            def writer = new PrintWriter(client.outputStream, true)
            
            String command = reader.readLine()
            Logger.info("Received command: $command")
            
            if (command == "shutdown") {
                writer.println("Shutting down daemon...")
                running = false
            } else if (command == "reload") {
                // Reload build.groovy and plugins
                engine.tasks.clear()
                engine.defaultTasks.clear()
                loadBuildFile()
                engine.loadPlugins()
                writer.println("Reloaded successfully.")
            } else {
                // Execute task
                List<String> tasksToRun = command.trim() ? command.split(' ').toList() : engine.defaultTasks
                
                // Redirect output to client
                def outputBuffer = new StringBuilder()
                def originalOut = System.out
                def originalErr = System.err
                
                System.out = new PrintStream(new OutputStream() {
                    void write(int b) { outputBuffer.append((char) b) }
                })
                
                try {
                    engine.run(tasksToRun as String[])
                    writer.println("BUILD SUCCESSFUL")
                } catch (Exception e) {
                    writer.println("BUILD FAILED: ${e.message}")
                }
                
                System.out = originalOut
                System.err = originalErr
                
                writer.println(outputBuffer.toString())
            }
            
            writer.println("END_OF_OUTPUT")
            client.close()
        } catch (Exception e) {
            Logger.error("Client handling error: ${e.message}")
        }
    }
}

// --- CLI WRAPPER LOGIC ---
// Detect if running as daemon or client
if (args.length > 0 && args[0] == "--daemon") {
    // Run as daemon
    new GraphonDaemon().start()
} else {
    // Run as client (direct execution)
    new Graphon().run(args)
}

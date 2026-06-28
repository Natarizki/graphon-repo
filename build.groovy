// build.groovy

srcDir = "src"
buildDir = "build"

task('clean') {
    description "Clean build directory"
    doLast {
        exec "rm -rf ${buildDir}"
        exec "mkdir -p ${buildDir}"
    }
}

task('compile') {
    // Hapus dependsOn 'clean' agar cache tidak hilang saat test incremental
    description "Compile C code"
    inputs "${srcDir}/main.c"
    outputs "${buildDir}/main.o"
    doLast {
        exec "clang -c ${srcDir}/main.c -o ${buildDir}/main.o"
    }
}

task('link') {
    dependsOn 'compile'
    inputs "${buildDir}/main.o"
    outputs "${buildDir}/app"
    doLast {
        exec "clang ${buildDir}/main.o -o ${buildDir}/app"
    }
}

task('run') {
    dependsOn 'link'
    doLast {
        exec "./${buildDir}/app"
    }
}

defaultTasks 'run'

// plugins/debug.groovy

// Plugin ini menambahkan task 'debug' yang compail dengan flag -g
task('debug') {
    description "Compile with debug symbols"
    dependsOn 'clean'
    inputs "${srcDir}/main.c"
    outputs "${buildDir}/main_debug.o"
    doLast {
        exec "clang -g -c ${srcDir}/main.c -o ${buildDir}/main_debug.o"
        exec "clang ${buildDir}/main_debug.o -o ${buildDir}/app_debug"
        exec "./${buildDir}/app_debug"
    }
}

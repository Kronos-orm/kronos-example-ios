@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kronos) apply false
}

// Full ORM integration is the default. Baseline contract isolation must opt out explicitly.
val enableOrm = providers.gradleProperty("enableOrm").map(String::toBoolean).getOrElse(true)
if (enableOrm) apply(plugin = "com.kotlinorm.kronos-gradle-plugin")

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xcollection-literals") }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosX64()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    listOf(macosX64(), macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "com.kotlinorm.example.ios.main"
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KronosIosExample"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            if (enableOrm) kotlin.srcDir("src/ormMain/kotlin")
            dependencies {
                implementation(libs.kronos.core)
            }
        }
        val nativeMain = getByName("nativeMain") {
            kotlin.srcDir(if (enableOrm) "src/ormNativeMain/kotlin" else "src/rawMain/kotlin")
        }
        val bundledMain = create("bundledMain") {
            dependsOn(nativeMain)
            dependencies {
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        val cApiMain = create("cApiMain") { dependsOn(nativeMain) }
        targets.withType<KotlinNativeTarget>().configureEach {
            val useC = name == "mingwX64" ||
                (name == "macosX64" && providers.gradleProperty("sqliteBackend").orNull == "c")
            getByName("${name}Main").dependsOn(if (useC) cApiMain else bundledMain)
        }
        getByName("commonTest") {
            if (enableOrm) kotlin.srcDir("src/ormTest/kotlin")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// Compile the application-owned SQLite fallback. Windows gates provide MinGW GCC/ar;
// -PsqliteBackend=c also runs this backend's exact shared tests on Intel macOS.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    if (name != "mingwX64" && !(name == "macosX64" && providers.gradleProperty("sqliteBackend").orNull == "c")) return@configureEach
    val targetName = name
    val sqliteSource = layout.projectDirectory.dir("src/nativeInterop/sqlite")
    val outputDir = layout.buildDirectory.dir("sqlite/$targetName")
    val objectFile = outputDir.map { it.file("sqlite3.o") }
    val archiveFile = outputDir.map { it.file("libsqlite3.a") }
    val compileSqlite = tasks.register<Exec>("compileSqlite${targetName.replaceFirstChar(Char::uppercaseChar)}") {
        inputs.files(sqliteSource.file("sqlite3.c"), sqliteSource.file("sqlite3.h"))
        outputs.file(objectFile)
        doFirst { outputDir.get().asFile.mkdirs() }
        commandLine(providers.gradleProperty("sqliteCc").getOrElse(if (targetName == "mingwX64") "gcc" else "clang"),
            "-O2", "-DSQLITE_THREADSAFE=1", "-DSQLITE_OMIT_LOAD_EXTENSION=1", "-c",
            sqliteSource.file("sqlite3.c").asFile.absolutePath, "-o", objectFile.get().asFile.absolutePath)
    }
    val archiveSqlite = tasks.register<Exec>("archiveSqlite${targetName.replaceFirstChar(Char::uppercaseChar)}") {
        dependsOn(compileSqlite)
        inputs.file(objectFile)
        outputs.file(archiveFile)
        commandLine(providers.gradleProperty("sqliteAr").getOrElse("ar"), "rcs",
            archiveFile.get().asFile.absolutePath, objectFile.get().asFile.absolutePath)
    }
    val interop = compilations.getByName("main").cinterops.create("sqlite") {
        defFile(project.file("src/nativeInterop/sqlite.def"))
        includeDirs(sqliteSource.asFile)
        extraOpts("-libraryPath", outputDir.get().asFile.absolutePath)
    }
    tasks.named(interop.interopProcessingTaskName).configure { dependsOn(archiveSqlite) }
}

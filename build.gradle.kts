import com.google.protobuf.gradle.id
import java.io.File

plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("org.jetbrains.kotlin.kapt") version "2.1.21"
    id("com.google.protobuf") version "0.9.4"
}

val appId = "dev.azagra.trebuchet_lineageos"
val apkBaseName = "dev.azagra.trebuchet-lineageos"
val appVersionName = (findProperty("versionName") as String?) ?: "16.0.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

val systemUiRepoDir = file("systemui-libs")
val mergedProtosDir = layout.buildDirectory.dir("generated/merged-protos")
val generatedAospCompatDir = layout.buildDirectory.dir("generated/source/aospCompat")
val generatedSystemUiSrcDir = layout.buildDirectory.dir("generated/source/systemuiLibs")

val hiddenApiCompileOnly: Configuration by configurations.creating

val syncSystemUiLibs by tasks.registering {
    outputs.dir(systemUiRepoDir)
    doLast {
        if (!File(systemUiRepoDir, ".git").exists()) {
            systemUiRepoDir.deleteRecursively()
            val process = ProcessBuilder(
                "git", "clone", "--depth", "1", "-b", "lineage-22.2",
                "https://github.com/LineageOS/android_frameworks_libs_systemui.git",
                systemUiRepoDir.absolutePath,
            ).inheritIO().start()
            val exit = process.waitFor()
            if (exit != 0) {
                throw GradleException("Failed to clone LineageOS/android_frameworks_libs_systemui (exit $exit)")
            }
        }
    }
}

val prepareMergedProtos by tasks.registering {
    dependsOn(syncSystemUiLibs)
    outputs.dir(mergedProtosDir)
    doLast {
        val outDir = mergedProtosDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val protoDirs = listOf(
            file("protos"),
            file("protos_overrides"),
            file("quickstep/protos_overrides"),
            File(systemUiRepoDir, "viewcapturelib/proto"),
        )
        protoDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "proto" }.forEach { protoFile ->
                val rel = protoFile.relativeTo(dir).path
                val dest = File(outDir, rel)
                dest.parentFile.mkdirs()
                protoFile.copyTo(dest, overwrite = true)
            }
        }
    }
}

val prepareSystemUiSources by tasks.registering {
    dependsOn(syncSystemUiLibs)
    outputs.dir(generatedSystemUiSrcDir)
    doLast {
        val outDir = generatedSystemUiSrcDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val localSrcRoots = listOf(
            file("src"),
            file("src_no_quickstep"),
            file("src_build_config"),
            file("src_plugins"),
            file("tests/multivalentTests/shared"),
        )
        val existingRelPaths = mutableSetOf<String>()
        localSrcRoots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.forEach { f ->
                existingRelPaths.add(f.relativeTo(root).path.substringBeforeLast("."))
            }
        }

        val moduleSrcDirs = listOf(
            File(systemUiRepoDir, "iconloaderlib/src"),
            File(systemUiRepoDir, "iconloaderlib/src_full_lib"),
            File(systemUiRepoDir, "viewcapturelib/src"),
            File(systemUiRepoDir, "animationlib/src"),
            File(systemUiRepoDir, "contextualeducationlib/src"),
            File(systemUiRepoDir, "msdl/src"),
            File(systemUiRepoDir, "plugin_core/src"),
        )

        val rImportRegex = Regex("""import\s+(com\.android\.launcher3\.icons|com\.android\.app\.animation|com\.android\.systemui\.animation|com\.android\.app\.viewcapture|com\.google\.android\.msdl)\.R(\s*;?)""")

        moduleSrcDirs.filter { it.exists() }.forEach { srcRoot ->
            srcRoot.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.forEach { srcFile ->
                val rel = srcFile.relativeTo(srcRoot).path
                val key = rel.substringBeforeLast(".")
                if (key !in existingRelPaths) {
                    existingRelPaths.add(key)
                    val dest = File(outDir, rel)
                    dest.parentFile.mkdirs()
                    val rewritten = srcFile.readText().replace(rImportRegex) { m ->
                        "import com.android.launcher3.R${m.groupValues[2]}"
                    }
                    dest.writeText(rewritten)
                }
            }
        }
    }
}

val generateAospCompatSources by tasks.registering {
    dependsOn(prepareSystemUiSources)
    outputs.dir(generatedAospCompatDir)
    doLast {
        val outDir = generatedAospCompatDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        fun snakeToCamel(name: String): String {
            val parts = name.split("_").filter { it.isNotEmpty() }
            if (parts.isEmpty()) return name
            return parts.first().lowercase() + parts.drop(1).joinToString("") { part ->
                part.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
        }

        fun camelToUpperSnake(camel: String): String =
            camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

        val flagsByPackage = mutableMapOf<String, MutableSet<String>>()

        val aconfigRoots = listOf(file("aconfig"), systemUiRepoDir)
        val pkgRegex = Regex("""package:\s*"([^"]+)"""")
        val flagNameRegex = Regex("""flag\s*\{[^}]*?\bname:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

        aconfigRoots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "aconfig" }.forEach { af ->
                val content = af.readText()
                val pkg = pkgRegex.find(content)?.groupValues?.get(1) ?: "com.android.launcher3"
                val set = flagsByPackage.getOrPut(pkg) { linkedSetOf() }
                flagNameRegex.findAll(content).forEach { m ->
                    set.add(snakeToCamel(m.groupValues[1]))
                }
            }
        }

        val scanRoots = listOf(
            file("src"),
            file("src_no_quickstep"),
            file("src_plugins"),
            generatedSystemUiSrcDir.get().asFile,
        )
        val staticFlagImport = Regex("""import\s+static\s+([a-zA-Z0-9_.]+)\.Flags\.([a-zA-Z0-9_]+)\s*;""")
        val flagImport = Regex("""import\s+([a-zA-Z0-9_.]+)\.Flags\s*;""")
        val fqFlagCall = Regex("""\b([a-zA-Z0-9_.]+)\.Flags\.([a-zA-Z0-9_]+)\s*\(""")
        val shortFlagCall = Regex("""\bFlags\.([a-zA-Z0-9_]+)\s*\(""")
        val statsLogConst = Regex("""\bSysUiStatsLog\.([A-Z0-9_]+)\b""")
        val statsLogConstants = linkedSetOf<String>()

        scanRoots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.forEach { sf ->
                val text = sf.readText()
                staticFlagImport.findAll(text).forEach { m ->
                    val pkg = m.groupValues[1]
                    val member = m.groupValues[2]
                    if (!member.startsWith("FLAG_")) {
                        flagsByPackage.getOrPut(pkg) { linkedSetOf() }.add(member)
                    }
                }
                fqFlagCall.findAll(text).forEach { m ->
                    flagsByPackage.getOrPut(m.groupValues[1]) { linkedSetOf() }.add(m.groupValues[2])
                }
                val importedFlagPkg = flagImport.find(text)?.groupValues?.get(1) ?: "com.android.launcher3"
                shortFlagCall.findAll(text).forEach { m ->
                    flagsByPackage.getOrPut(importedFlagPkg) { linkedSetOf() }.add(m.groupValues[1])
                }
                statsLogConst.findAll(text).forEach { m ->
                    statsLogConstants.add(m.groupValues[1])
                }
            }
        }

        flagsByPackage.forEach { (pkg, methods) ->
            val pkgDir = File(outDir, pkg.replace('.', '/'))
            pkgDir.mkdirs()
            val sb = StringBuilder()
            sb.appendLine("package $pkg;")
            sb.appendLine("public final class Flags {")
            methods.sorted().forEach { method ->
                val constName = "FLAG_${camelToUpperSnake(method)}"
                sb.appendLine("    public static final String $constName = \"$pkg.${method}\";")
                sb.appendLine("    public static boolean $method() { return false; }")
            }
            sb.appendLine("}")
            File(pkgDir, "Flags.java").writeText(sb.toString())
        }

        val statsDir = File(outDir, "com/android/systemui/shared/system")
        statsDir.mkdirs()
        val statsSb = StringBuilder()
        statsSb.appendLine("package com.android.systemui.shared.system;")
        statsSb.appendLine("public final class SysUiStatsLog {")
        statsLogConstants.forEachIndexed { idx, name ->
            statsSb.appendLine("    public static final int $name = ${idx + 1};")
        }
        statsSb.appendLine("    public static void write(int code, Object... args) {}")
        statsSb.appendLine("}")
        File(statsDir, "SysUiStatsLog.java").writeText(statsSb.toString())
    }
}

android {
    namespace = "com.android.launcher3"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["packageName"] = appId
    }

    flavorDimensions += "edition"
    productFlavors {
        create("trebuchet") {
            dimension = "edition"
        }
    }

    val keystorePath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null && File(keystorePath).exists()) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("release")?.let {
                signingConfig = it
                isDebuggable = false
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.flags")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest-common.xml")
            java.setSrcDirs(
                listOf(
                    "src",
                    "src_no_quickstep",
                    "src_build_config",
                    "src_plugins",
                    "compose/facade/core",
                    "compose/facade/disabled",
                    "tests/multivalentTests/shared",
                    generatedSystemUiSrcDir,
                    generatedAospCompatDir,
                )
            )
            res.setSrcDirs(
                listOf(
                    "res",
                    "systemui-libs/iconloaderlib/res",
                    "systemui-libs/animationlib/res",
                )
            )
            assets.setSrcDirs(listOf("assets"))
            withGroovyBuilder {
                getProperty("proto").withGroovyBuilder {
                    "setSrcDirs"(listOf(mergedProtosDir))
                }
            }
        }
        named("trebuchet") {
            manifest.srcFile("AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    applicationVariants.all {
        val isDebug = buildType.name == "debug"
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (isDebug) "$apkBaseName-$appVersionName-$appVersionCode.apk" else "$apkBaseName-$appVersionName.apk"
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().configureEach {
            dependsOn(prepareMergedProtos)
            builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") {
    dependsOn(syncSystemUiLibs, prepareMergedProtos, prepareSystemUiSources, generateAospCompatSources)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateAospCompatSources, prepareSystemUiSources)
    doFirst {
        options.bootstrapClasspath = files(hiddenApiCompileOnly.files, options.bootstrapClasspath)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateAospCompatSources, prepareSystemUiSources)
}

dependencies {
    hiddenApiCompileOnly("org.robolectric:android-all:15-robolectric-12650502")
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")

    implementation(files("libs/libGoogleFeed.jar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.slice:slice-core:1.0.0")
    implementation("androidx.slice:slice-view:1.0.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.window:window:1.3.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("com.google.dagger:dagger:2.52")
    kapt("com.google.dagger:dagger-compiler:2.52")
    implementation("javax.inject:javax.inject:1")
}

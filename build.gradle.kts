import com.google.protobuf.gradle.id
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node

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
val generatedSystemUiResDir = layout.buildDirectory.dir("generated/res/systemuiLibs")

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

val prepareSystemUiResources by tasks.registering {
    dependsOn(syncSystemUiLibs)
    outputs.dir(generatedSystemUiResDir)
    doLast {
        val outDir = generatedSystemUiResDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        fun normalizeValueType(el: Element): String {
            val tag = el.tagName
            return when (tag) {
                "item" -> el.getAttribute("type").ifBlank { "item" }
                "string-array", "integer-array", "array" -> "array"
                else -> tag
            }
        }

        val definedValues = mutableSetOf<Pair<String, String>>()
        val definedAttrs = mutableSetOf<String>()
        val definedFiles = mutableSetOf<Pair<String, String>>()

        val dbf = DocumentBuilderFactory.newInstance()
        val mainRes = file("res")
        if (mainRes.exists()) {
            mainRes.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                val typePrefix = subDir.name.substringBefore("-")
                if (typePrefix == "values") {
                    subDir.listFiles()?.filter { it.isFile && it.extension == "xml" }?.forEach { xmlFile ->
                        runCatching {
                            val doc = dbf.newDocumentBuilder().parse(xmlFile)
                            val root = doc.documentElement
                            val children = root.childNodes
                            for (i in 0 until children.length) {
                                val node = children.item(i)
                                if (node is Element) {
                                    val name = node.getAttribute("name")
                                    if (name.isNotBlank()) {
                                        val vType = normalizeValueType(node)
                                        definedValues.add(vType to name)
                                        if (vType == "attr") definedAttrs.add(name)
                                    }
                                    if (node.tagName == "declare-styleable") {
                                        val inner = node.childNodes
                                        for (j in 0 until inner.length) {
                                            val innerNode = inner.item(j)
                                            if (innerNode is Element && innerNode.tagName == "attr") {
                                                val attrName = innerNode.getAttribute("name")
                                                if (attrName.isNotBlank()) {
                                                    definedAttrs.add(attrName)
                                                    definedValues.add("attr" to attrName)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    subDir.listFiles()?.filter { it.isFile }?.forEach { resFile ->
                        val baseName = resFile.name.substringBefore(".")
                        definedFiles.add(typePrefix to baseName)
                    }
                }
            }
        }

        val moduleResDirs = listOf(
            "iconloader" to File(systemUiRepoDir, "iconloaderlib/res"),
            "animation" to File(systemUiRepoDir, "animationlib/res"),
            "viewcapture" to File(systemUiRepoDir, "viewcapturelib/res"),
        )

        val tf = TransformerFactory.newInstance()
        moduleResDirs.filter { it.second.exists() }.forEach { (prefix, resRoot) ->
            resRoot.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                val typePrefix = subDir.name.substringBefore("-")
                if (typePrefix == "values") {
                    subDir.listFiles()?.filter { it.isFile && it.extension == "xml" }?.forEach { xmlFile ->
                        runCatching {
                            val doc = dbf.newDocumentBuilder().parse(xmlFile)
                            val root = doc.documentElement
                            val toRemove = mutableListOf<Node>()
                            var keptCount = 0
                            val children = root.childNodes
                            for (i in 0 until children.length) {
                                val node = children.item(i)
                                if (node is Element) {
                                    val name = node.getAttribute("name")
                                    val vType = normalizeValueType(node)
                                    if (name.isNotBlank() && (vType to name) in definedValues) {
                                        toRemove.add(node)
                                    } else {
                                        if (name.isNotBlank()) {
                                            definedValues.add(vType to name)
                                            if (vType == "attr") definedAttrs.add(name)
                                        }
                                        if (node.tagName == "declare-styleable") {
                                            val inner = node.childNodes
                                            for (j in 0 until inner.length) {
                                                val innerNode = inner.item(j)
                                                if (innerNode is Element && innerNode.tagName == "attr") {
                                                    val attrName = innerNode.getAttribute("name")
                                                    if (attrName in definedAttrs) {
                                                        innerNode.removeAttribute("format")
                                                        while (innerNode.hasChildNodes()) {
                                                            innerNode.removeChild(innerNode.firstChild)
                                                        }
                                                    } else if (attrName.isNotBlank()) {
                                                        definedAttrs.add(attrName)
                                                        definedValues.add("attr" to attrName)
                                                    }
                                                }
                                            }
                                        }
                                        keptCount++
                                    }
                                }
                            }
                            toRemove.forEach { root.removeChild(it) }
                            if (keptCount > 0) {
                                val destDir = File(outDir, subDir.name)
                                destDir.mkdirs()
                                val destFile = File(destDir, "${prefix}_${xmlFile.name}")
                                val transformer = tf.newTransformer().apply {
                                    setOutputProperty(OutputKeys.INDENT, "yes")
                                }
                                destFile.outputStream().use { outStream ->
                                    transformer.transform(DOMSource(doc), StreamResult(outStream))
                                }
                            }
                        }
                    }
                } else {
                    subDir.listFiles()?.filter { it.isFile }?.forEach { resFile ->
                        val baseName = resFile.name.substringBefore(".")
                        val key = typePrefix to baseName
                        if (key !in definedFiles) {
                            definedFiles.add(key)
                            val destDir = File(outDir, subDir.name)
                            destDir.mkdirs()
                            resFile.copyTo(File(destDir, resFile.name), overwrite = true)
                        }
                    }
                }
            }
        }
    }
}

val prepareMergedProtos by tasks.registering {
    dependsOn(syncSystemUiLibs)
    outputs.dir(mergedProtosDir)
    outputs.upToDateWhen { false }
    doLast {
        val outDir = mergedProtosDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val protoDirs = listOf(
            file("protos"),
            file("protos_overrides"),
            file("quickstep/protos_overrides"),
        )
        protoDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "proto" }.forEach { protoFile ->
                val rel = protoFile.relativeTo(dir).path
                val dest = File(outDir, rel)
                dest.parentFile.mkdirs()
                protoFile.copyTo(dest, overwrite = true)
            }
        }

        val viewCaptureDir = File(systemUiRepoDir, "viewcapturelib")
        if (viewCaptureDir.exists()) {
            viewCaptureDir.walkTopDown().filter { it.isFile && it.extension == "proto" }.forEach { protoFile ->
                val dest = File(outDir, protoFile.name)
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
        val rRefRegex = Regex("""\bR\.""")
        val pkgRegex = Regex("""^(\s*package\s+([^\s;]+)\s*;?\s*)$""", RegexOption.MULTILINE)

        moduleSrcDirs.filter { it.exists() }.forEach { srcRoot ->
            srcRoot.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.forEach { srcFile ->
                val rel = srcFile.relativeTo(srcRoot).path
                val key = rel.substringBeforeLast(".")
                if (key !in existingRelPaths) {
                    existingRelPaths.add(key)
                    val dest = File(outDir, rel)
                    dest.parentFile.mkdirs()
                    var rewritten = srcFile.readText().replace(rImportRegex) { m ->
                        "import com.android.launcher3.R${m.groupValues[2]}"
                    }
                    if (!rewritten.contains("import com.android.launcher3.R") && rRefRegex.containsMatchIn(rewritten)) {
                        val pkgMatch = pkgRegex.find(rewritten)
                        if (pkgMatch != null && pkgMatch.groupValues[2] != "com.android.launcher3") {
                            val semi = if (srcFile.extension == "java") ";" else ""
                            rewritten = rewritten.replaceFirst(pkgMatch.groupValues[1], "${pkgMatch.groupValues[1]}\n\nimport com.android.launcher3.R$semi")
                        }
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
    outputs.upToDateWhen { false }
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

        val taskbarDir = File(outDir, "com/android/launcher3/taskbar")
        taskbarDir.mkdirs()
        File(taskbarDir, "TaskbarManager.java").writeText(
            """
            package com.android.launcher3.taskbar;
            import android.net.Uri;
            import android.provider.Settings;
            public final class TaskbarManager {
                public static final Uri NAV_BAR_INVERSE = Settings.Secure.getUriFor("sysui_nav_bar_inverse");
                public static final Uri ENABLE_TASKBAR = Settings.System.getUriFor("enable_taskbar");
                public static final Uri NAVIGATION_BAR_HINT = Settings.System.getUriFor("navigation_bar_hint");
                public static final Uri FORCE_SHOW_NAVBAR = Settings.System.getUriFor("force_show_navbar");
            }
            """.trimIndent()
        )

        val pluginsDir = File(outDir, "com/android/systemui/plugins")
        pluginsDir.mkdirs()
        File(pluginsDir, "Plugin.java").writeText(
            """
            package com.android.systemui.plugins;
            import android.content.Context;
            public interface Plugin {
                default int getVersion() { return -1; }
                default void onCreate(Context appContext, Context pluginContext) {}
                default void onDestroy() {}
            }
            """.trimIndent()
        )
        File(pluginsDir, "PluginListener.java").writeText(
            """
            package com.android.systemui.plugins;
            import android.content.Context;
            public interface PluginListener<T extends Plugin> {
                default void onPluginConnected(T plugin, Context pluginContext) {}
                default void onPluginDisconnected(T plugin) {}
                default boolean onPluginLoaded(T plugin, Context pluginContext, PluginLifecycleManager<T> manager) {
                    onPluginConnected(plugin, pluginContext);
                    return true;
                }
                default void onPluginUnloaded(T plugin, PluginLifecycleManager<T> manager) {
                    onPluginDisconnected(plugin);
                }
            }
            """.trimIndent()
        )
        File(pluginsDir, "PluginLifecycleManager.java").writeText(
            """
            package com.android.systemui.plugins;
            import android.content.ComponentName;
            public interface PluginLifecycleManager<T extends Plugin> {
                ComponentName getComponentName();
                String getPackage();
                default T getPlugin() { return null; }
                default void loadPlugin() {}
                default void unloadPlugin() {}
            }
            """.trimIndent()
        )
        File(pluginsDir, "PluginManager.java").writeText(
            """
            package com.android.systemui.plugins;
            public interface PluginManager {
                String PLUGIN_CHANGED = "com.android.systemui.action.PLUGIN_CHANGED";
                String NOTIFICATION_CHANNEL_ID = "ALR";
                <T extends Plugin> void addPluginListener(PluginListener<T> listener, Class<T> cls);
                <T extends Plugin> void addPluginListener(PluginListener<T> listener, Class<T> cls, boolean allowMultiple);
                <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener, Class<T> cls);
                <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener, Class<T> cls, boolean allowMultiple);
                void removePluginListener(PluginListener<?> listener);
                <T> boolean dependsOn(Plugin p, Class<T> cls);
                String[] getPrivilegedPlugins();
            }
            """.trimIndent()
        )

        val lineageDir = File(outDir, "lineageos/providers")
        lineageDir.mkdirs()
        File(lineageDir, "LineageSettings.java").writeText(
            """
            package lineageos.providers;
            import android.content.ContentResolver;
            import android.net.Uri;
            import android.provider.Settings;
            public final class LineageSettings {
                public static final class System {
                    public static final Uri CONTENT_URI = Settings.System.CONTENT_URI;
                    public static int getInt(ContentResolver cr, String name, int def) {
                        return Settings.System.getInt(cr, name, def);
                    }
                    public static String getString(ContentResolver cr, String name) {
                        return Settings.System.getString(cr, name);
                    }
                }
            }
            """.trimIndent()
        )

        val msdlModelDir = File(outDir, "com/google/android/msdl/data/model")
        msdlModelDir.mkdirs()
        File(msdlModelDir, "MSDLToken.java").writeText(
            """
            package com.google.android.msdl.data.model;
            public enum MSDLToken {
                DRAG_INDICATOR_DISCRETE,
                SWIPE_THRESHOLD_INDICATOR,
                TAP_HIGH_EMPHASIS,
                TAP_MEDIUM_EMPHASIS,
                TAP_LOW_EMPHASIS,
                KEYPRESS_STANDARD,
                KEYPRESS_SPACEBAR,
                KEYPRESS_DELETE,
                KEYPRESS_RETURN,
                LONG_PRESS,
                DRAG_INDICATOR_CONTINUOUS,
                FAILURE,
                FAILURE_HIGH_EMPHASIS,
                SUCCESS,
                UNLOCK,
                PAUSE_TIMER,
                STOP_TIMER,
                CANCEL,
                SELECT
            }
            """.trimIndent()
        )

        val msdlLoggingDir = File(outDir, "com/google/android/msdl/logging")
        msdlLoggingDir.mkdirs()
        File(msdlLoggingDir, "MSDLEvent.java").writeText(
            """
            package com.google.android.msdl.logging;
            public final class MSDLEvent {}
            """.trimIndent()
        )

        val msdlDomainDir = File(outDir, "com/google/android/msdl/domain")
        msdlDomainDir.mkdirs()
        File(msdlDomainDir, "InteractionProperties.java").writeText(
            """
            package com.google.android.msdl.domain;
            public interface InteractionProperties {}
            """.trimIndent()
        )
        File(msdlDomainDir, "MSDLPlayer.java").writeText(
            """
            package com.google.android.msdl.domain;
            import android.os.Vibrator;
            import com.google.android.msdl.data.model.MSDLToken;
            import com.google.android.msdl.logging.MSDLEvent;
            import java.util.Collections;
            import java.util.List;
            import java.util.concurrent.Executor;
            public interface MSDLPlayer {
                void playToken(MSDLToken token, InteractionProperties properties);
                default List<MSDLEvent> getHistory() { return Collections.emptyList(); }
                final class Companion {
                    private Companion() {}
                    public MSDLPlayer createPlayer(Vibrator vibrator, Executor executor, Object feedbackLevel) {
                        return (token, properties) -> {};
                    }
                }
                Companion Companion = new Companion();
            }
            """.trimIndent()
        )

        val pluginAnnotationsDir = File(outDir, "com/android/systemui/plugins/annotations")
        pluginAnnotationsDir.mkdirs()
        File(pluginAnnotationsDir, "ProvidesInterface.java").writeText(
            """
            package com.android.systemui.plugins.annotations;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface ProvidesInterface {
                String action() default "";
                int version();
            }
            """.trimIndent()
        )
        File(pluginAnnotationsDir, "Requires.java").writeText(
            """
            package com.android.systemui.plugins.annotations;
            import java.lang.annotation.Repeatable;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            @Repeatable(Requirements.class)
            public @interface Requires {
                Class<?> target();
                int version();
            }
            """.trimIndent()
        )
        File(pluginAnnotationsDir, "Requirements.java").writeText(
            """
            package com.android.systemui.plugins.annotations;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Requirements {
                Requires[] value();
            }
            """.trimIndent()
        )
        File(pluginAnnotationsDir, "DependsOn.java").writeText(
            """
            package com.android.systemui.plugins.annotations;
            import java.lang.annotation.Repeatable;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            @Repeatable(Dependencies.class)
            public @interface DependsOn {
                Class<?> target();
            }
            """.trimIndent()
        )
        File(pluginAnnotationsDir, "Dependencies.java").writeText(
            """
            package com.android.systemui.plugins.annotations;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Dependencies {
                DependsOn[] value();
            }
            """.trimIndent()
        )
        File(pluginAnnotationsDir, "ProtectedInterface.java").writeText(
            """
            package com.android.systemui.plugins.annotations;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface ProtectedInterface {
                String[] exTypes() default {};
            }
            """.trimIndent()
        )
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
            res.setSrcDirs(listOf(generatedSystemUiResDir))
            assets.setSrcDirs(listOf("assets"))
            withGroovyBuilder {
                getProperty("proto").withGroovyBuilder {
                    "setSrcDirs"(listOf(mergedProtosDir))
                }
            }
        }
        named("trebuchet") {
            manifest.srcFile("AndroidManifest.xml")
            res.setSrcDirs(listOf("res"))
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
            outputs.upToDateWhen { false }
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
        freeCompilerArgs.add("-Xnullability-annotations=@android.annotation:ignore")
    }
}

tasks.matching {
    it.name != syncSystemUiLibs.name &&
        it.name != prepareSystemUiResources.name &&
        it.name != prepareSystemUiSources.name &&
        it.name != generateAospCompatSources.name &&
        it.name != prepareMergedProtos.name &&
        (it.name.startsWith("pre") ||
            it.name.contains("Resources") ||
            it.name.contains("SourceSetPaths") ||
            it.name.contains("ResValues") ||
            it.name.contains("Manifest") ||
            it.name.contains("Aidl") ||
            it.name.contains("Kapt") ||
            it.name.contains("kapt"))
}.configureEach {
    dependsOn(syncSystemUiLibs, prepareSystemUiResources, prepareSystemUiSources, generateAospCompatSources, prepareMergedProtos)
}

gradle.projectsEvaluated {
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(generateAospCompatSources, prepareSystemUiSources, prepareSystemUiResources)
        classpath = files(hiddenApiCompileOnly, classpath)
        options.bootstrapClasspath = files(hiddenApiCompileOnly, options.bootstrapClasspath)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        dependsOn(generateAospCompatSources, prepareSystemUiSources, prepareSystemUiResources)
        val orig = libraries.from.toList()
        libraries.setFrom(hiddenApiCompileOnly, orig)
    }
}

dependencies {
    hiddenApiCompileOnly("org.robolectric:android-all:15-robolectric-12650502")
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")

    implementation(files("libs/libGoogleFeed.jar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-animation:1.0.0")
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

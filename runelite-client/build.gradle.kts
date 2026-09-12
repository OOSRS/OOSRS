/*
 * Copyright (c) 2019 Owain van Brakel <https://github.com/Owain94>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import org.apache.tools.ant.filters.ReplaceTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.github.johnrengelman.shadow") version "7.1.2"
    java
}

description = "OpenOSRS Client"

dependencies {
	implementation("org.json:json:20090211")
    annotationProcessor(group = "org.projectlombok", name = "lombok", version = ProjectVersions.lombokVersion)
    // annotationProcessor(group = "org.pf4j", name = "pf4j", version = "3.6.0")

    api(project(":runelite-api"))
	implementation(project(":openosrs-api"))

    compileOnly(group = "javax.annotation", name = "javax.annotation-api", version = "1.3.2")
    compileOnly(group = "org.projectlombok", name = "lombok", version = ProjectVersions.lombokVersion)
    compileOnly(group = "net.runelite", name = "orange-extensions", version = "1.0")

    implementation(project(":http-api"))
    implementation(project(":runelite-jshell"))
    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.2.9")
    implementation(group = "com.google.code.gson", name = "gson", version = "2.8.5")
    implementation(group = "com.google.guava", name = "guava", version = "30.1.1-jre") {
        exclude(group = "com.google.code.findbugs", module = "jsr305")
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
        exclude(group = "com.google.j2objc", module = "j2objc-annotations")
        exclude(group = "org.codehaus.mojo", module = "animal-sniffer-annotations")
    }
    implementation(group = "com.google.inject", name = "guice", version = "5.0.1")
    implementation(group = "com.google.protobuf", name = "protobuf-javalite", version = "3.21.1")
    implementation(group = "com.jakewharton.rxrelay3", name = "rxrelay", version = "3.0.1")
    implementation(group = "com.squareup.okhttp3", name = "okhttp", version = "4.9.1")
    implementation(group = "io.reactivex.rxjava3", name = "rxjava", version = "3.1.2")
    implementation(group = "org.jgroups", name = "jgroups", version = "5.2.2.Final")
    implementation(group = "net.java.dev.jna", name = "jna", version = "5.9.0")
    implementation(group = "net.java.dev.jna", name = "jna-platform", version = "5.9.0")
    implementation(group = "net.runelite", name = "discord", version = "1.4")
    implementation(group = "net.runelite.pushingpixels", name = "substance", version = "8.0.02")
    implementation(group = "net.sf.jopt-simple", name = "jopt-simple", version = "5.0.4")
    implementation(group = "org.madlonkay", name = "desktopsupport", version = "0.6.0")
    implementation(group = "org.apache.commons", name = "commons-text", version = "1.9")
    implementation(group = "org.apache.commons", name = "commons-csv", version = "1.9.0")
    implementation(group = "commons-io", name = "commons-io", version = "2.8.0")
    implementation(group = "org.jetbrains", name = "annotations", version = "22.0.0")
    implementation(group = "com.github.zafarkhaja", name = "java-semver", version = "0.9.0")
    implementation(group = "org.slf4j", name = "slf4j-api", version = "1.7.32")
    implementation(group = "org.pf4j", name = "pf4j", version = "3.6.0") {
        exclude(group = "org.slf4j")
    }
    implementation(group = "org.pf4j", name = "pf4j-update", version = "2.3.0")
    // implementation(group = "com.google.archivepatcher", name = "archive-patch-applier", version= "1.0.4")

    // Renderer and native bridge from the exact RuneLite 1.12.38 source contract.
    implementation("net.runelite:rlawt:1.8")
    implementation("org.lwjgl:lwjgl:3.3.2")
    implementation("org.lwjgl:lwjgl-opengl:3.3.2")
    implementation("org.lwjgl:lwjgl-opencl:3.3.2")
    for (platform in listOf("linux", "linux-arm64", "macos", "macos-arm64", "windows-x86", "windows", "windows-arm64")) {
        runtimeOnly("org.lwjgl:lwjgl:3.3.2:natives-$platform")
        runtimeOnly("org.lwjgl:lwjgl-opengl:3.3.2:natives-$platform")
    }

    runtimeOnly(group = "net.runelite.pushingpixels", name = "trident", version = "1.5.00")

    testAnnotationProcessor(group = "org.projectlombok", name = "lombok", version = ProjectVersions.lombokVersion)

    testCompileOnly(group = "org.projectlombok", name = "lombok", version = ProjectVersions.lombokVersion)

    testImplementation(group = "com.google.inject.extensions", name = "guice-grapher", version = "4.1.0")
    testImplementation(group = "com.google.inject.extensions", name = "guice-testlib", version = "4.1.0")
    testImplementation(group = "org.hamcrest", name = "hamcrest-library", version = "1.3")
    testImplementation(group = "junit", name = "junit", version = "4.12")
    testImplementation(group = "org.mockito", name = "mockito-core", version = "3.1.0")
    testImplementation(group = "org.mockito", name = "mockito-inline", version = "3.1.0")
    testImplementation(group = "com.squareup.okhttp3", name = "mockwebserver", version = "4.9.1")
    testImplementation(group = "org.slf4j", name = "slf4j-api", version = "1.7.32")
}

val gamepack = file("src/main/resources/injected-client.oprs")
val gamepackProperties = Properties().apply { rootProject.file("gamepack.properties").inputStream().use { load(it) } }
val gamepackSha = gamepackProperties.getProperty("sha256")
val prepareGamepack by tasks.registering {
    group = "openosrs"
    description = "Fetch the exact game dependency required by this source revision"
    outputs.file(gamepack)
    outputs.upToDateWhen { false }
    doLast {
        check(gamepackProperties.getProperty("version") == ProjectVersions.rlVersion &&
            gamepackProperties.getProperty("revision") == ProjectVersions.rsversion.toString()) {
            "Game dependency pins do not match the client version constants"
        }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!gamepack.isFile || digest(gamepack) != gamepackSha) {
            gamepack.parentFile.mkdirs()
            val temporary = File(gamepack.parentFile, ".gamepack-download")
            try {
                val url = URL("https://repo.runelite.net/net/runelite/injected-client/${ProjectVersions.rlVersion}/injected-client-${ProjectVersions.rlVersion}.jar")
                val connection = url.openConnection().apply { connectTimeout = 15000; readTimeout = 60000 }
                connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(digest(temporary) == gamepackSha) { "Game dependency checksum mismatch; existing file preserved" }
                Files.move(temporary.toPath(), gamepack.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
}

tasks {
    processResources {
        dependsOn(prepareGamepack)
        val tokens = mapOf(
            "project.version" to ProjectVersions.rlVersion,
            "rs.version" to ProjectVersions.rsversion.toString(),
            "open.osrs.version" to project.version.toString(),
            "open.osrs.builddate" to "2026-09-12",
            "plugin.path" to (project.findProperty("pluginPath")?.toString() ?: "")
        )
        inputs.properties(tokens)
        filesMatching("**/*.properties") {
            filter(ReplaceTokens::class, "tokens" to tokens)
            filteringCharset = "UTF-8"
        }
    }
    jar {
        manifest { attributes("Main-Class" to "net.openosrs.client.OpenOSRSMain") }
    }
    shadowJar {
        archiveFileName.set("openosrs-client-${project.version}.jar")
        mergeServiceFiles()
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/INDEX.LIST")
        manifest { attributes("Main-Class" to "net.openosrs.client.OpenOSRSMain", "Implementation-Version" to project.version) }
        from(rootProject.file("LICENSE")) { into("META-INF/openosrs") }
        from(rootProject.file("NOTICE")) { into("META-INF/openosrs") }
    }
    register<JavaExec>("runClient") {
        group = "openosrs"
        dependsOn("classes")
        classpath = project.sourceSets.main.get().runtimeClasspath
        mainClass.set("net.openosrs.client.OpenOSRSMain")
    }
}

// Inspect compiled outputs and the pinned binary without initializing game classes.
for (gate in listOf("verifyRuntimeAbi", "verifyReleaseAbi")) {
    tasks.register<JavaExec>(gate) {
        group = "verification"
        dependsOn("classes")
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("net.openosrs.api.tools.RuntimeAbiVerifier")
        if (gate == "verifyReleaseAbi") {
            systemProperty("openosrs.abiBaseline", rootProject.file("config/runtime-abi-deferred.json").absolutePath)
        }
        doFirst {
            args(gamepack.absolutePath, rootProject.file("gamepack.properties").absolutePath,
                layout.buildDirectory.file("reports/abi/${gate}.json").get().asFile.absolutePath)
            args(sourceSets.main.get().runtimeClasspath.files.filter { it.exists() }.map { it.absolutePath })
        }
    }
}

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer

allprojects {
    group = "com.openosrs"
    version = "1.0.2"
}

plugins {
    application
}

subprojects {
    apply(plugin = "java-library")

    project.extra["gitCommit"] = "restored-2026"
    project.extra["rootPath"] = rootDir.toString().replace("\\", "/")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    configurations.compileOnly.get().extendsFrom(configurations["annotationProcessor"])

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(11)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

application {
    mainClass.set("net.openosrs.client.OpenOSRSMain")
}

tasks {
    named<JavaExec>("run") {
        group = "openosrs"

        classpath = project(":runelite-client").the<SourceSetContainer>().getByName("main").runtimeClasspath
        enableAssertions = true
    }
}

configure(listOf(project(":openosrs-api"), project(":runelite-api"))) {
    extensions.configure<JavaPluginExtension> { withSourcesJar(); withJavadocJar() }
    val sources = extensions.getByType<SourceSetContainer>()
    val delombok = tasks.register<JavaExec>("delombok") {
        dependsOn("compileJava")
        classpath = configurations.getByName("annotationProcessor")
        mainClass.set("lombok.launch.Main")
        val output = layout.buildDirectory.dir("delombok")
        inputs.files(sources.getByName("main").allJava)
        outputs.dir(output)
        doFirst {
            args("delombok", "src/main/java", "-d", output.get().asFile.absolutePath,
                "--classpath", sources.getByName("main").compileClasspath.asPath, "--encoding", "UTF-8")
        }
    }
    tasks.withType<Javadoc>().configureEach {
        dependsOn(delombok)
        setSource(fileTree(layout.buildDirectory.dir("delombok")))
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        (options as StandardJavadocDocletOptions).encoding = "UTF-8"
    }
}

tasks.register<Zip>("apiDocs") {
    group = "openosrs"
    dependsOn(":openosrs-api:javadoc", ":runelite-api:javadoc")
    archiveFileName.set("openosrs-javadocs-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(project(":openosrs-api").layout.buildDirectory.dir("docs/javadoc")) { into("openosrs-api") }
    from(project(":runelite-api").layout.buildDirectory.dir("docs/javadoc")) { into("client-api") }
}

tasks.register("releaseArtifacts") {
    group = "openosrs"
    check(providers.gradleProperty("allowMavenLocal").orNull != "true") { "Release artifacts cannot use mavenLocal" }
    dependsOn(":runelite-client:shadowJar", ":openosrs-api:sourcesJar", ":runelite-api:sourcesJar", "apiDocs")
}

tasks.register("verifyRuntimeAbi") {
    group = "verification"
    dependsOn(":runelite-client:verifyRuntimeAbi")
}

tasks.register("verifyReleaseAbi") {
    group = "verification"
    dependsOn(":runelite-client:verifyReleaseAbi")
}

tasks.register<Exec>("verifyApiContracts") {
    group = "verification"
    dependsOn(":openosrs-api:test", ":runelite-client:test")
    commandLine("python3", rootProject.file("scripts/check_test_counts.py"))
    workingDir(rootProject.projectDir)
}

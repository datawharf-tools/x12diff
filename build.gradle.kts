import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "tools.datawharf"
version = "0.2.4"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

application {
    mainClass.set("tools.datawharf.x12diff.MainKt")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}

// Fat jar: build/libs/x12diff.jar
tasks.jar {
    archiveFileName.set("x12diff.jar")
    manifest { attributes["Main-Class"] = "tools.datawharf.x12diff.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Windows app image with bundled runtime: build/jpackage/x12diff/x12diff.exe
tasks.register<Exec>("appImage") {
    dependsOn(tasks.jar)
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    doFirst { dest.deleteRecursively() }
    commandLine(
        "jpackage", "--type", "app-image", "--name", "x12diff",
        "--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        "--main-jar", "x12diff.jar", "--main-class", "tools.datawharf.x12diff.MainKt",
        "--dest", dest.absolutePath, "--win-console",
        "--app-version", project.version.toString(), "--vendor", "Datawharf Tools",
        "--copyright", "Datawharf Tools",
        "--java-options", "-Xmx512m"
    )
}

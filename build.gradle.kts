plugins {
    java
}

group = "dev.minescripture"
version = "0.1.0"

java {
    // Paper 26.2's API ships as Java 25 bytecode, so the compiler toolchain
    // must be 25. Gradle itself still runs on 21 (see gradle.properties).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.84-stable")
    // Gson ships inside Paper at runtime; needed on the compile/test classpath only.
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

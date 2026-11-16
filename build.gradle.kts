plugins {
    java
}

group = "io.github.andrewiankidd"
version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    compileOnly("org.geysermc.geyser:api:2.12.1-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")

    // The flattener needs only Gson, which Paper provides at runtime.
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Tests write their renders here, for looking at.
    systemProperty("persona.out", layout.buildDirectory.dir("test-renders").get().asFile.path)
    // -Ppersona.dump=<dir> renders a /personaskins dump as well.
    systemProperty("persona.dump", providers.gradleProperty("persona.dump").getOrElse(""))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

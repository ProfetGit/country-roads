plugins {
    id("net.minecraftforge.gradle")
}

val mc = stonecutter.current.version
val modId = property("mod.id") as String
val mcNext = mc.split(".").let { "${it[0]}.${it[1].toInt() + 1}" }

version = "${property("mod.version")}+$mc-forge"
group = property("mod.group") as String
base.archivesName = modId

minecraft {
    runs {
        configureEach {
            workingDir.convention(layout.projectDirectory.dir("run"))
            args("--mixin.config=$modId.mixins.json", "--mixin.config=$modId.chunkdata.mixins.json")
        }
        register("client") {
            args("--username", "Surveyor")
        }
    }
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$mc-${property("deps.forge")}"))
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

// Forge's patched Minecraft needs javac 26 or newer to read; use the JDK Gradle itself runs on when that is newer
val forgeJavac = maxOf(27, JavaVersion.current().majorVersion.toInt())

tasks.withType<JavaCompile>().configureEach {
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(forgeJavac) }
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["MixinConfigs"] = "$modId.mixins.json,$modId.chunkdata.mixins.json"
    }
}

tasks.processResources {
    exclude("fabric.mod.json", "META-INF/neoforge.mods.toml")
    val props = mapOf(
        "version" to project.version.toString(),
        "mc" to mc,
        "mc_next" to mcNext,
        "name" to project.property("mod.name"),
        "description" to project.property("mod.description"),
        "author" to project.property("mod.author"),
        "homepage" to project.property("mod.homepage"),
    )
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") { expand(props) }
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE"))
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

plugins {
    id("net.fabricmc.fabric-loom-remap")
}

val mc = stonecutter.current.version
val javaVer = if (mc.startsWith("1.20")) 17 else 21
val modId = property("mod.id") as String

version = "${property("mod.version")}+$mc-fabric"
group = property("mod.group") as String
base.archivesName = modId

repositories {
    maven("https://maven.terraformersmc.com/") {
        name = "TerraformersMC"
        content { includeGroup("com.terraformersmc") }
    }
}

dependencies {
    modCompileOnly("com.terraformersmc:modmenu:${property("deps.modmenu")}") { isTransitive = false }
    minecraft("com.mojang:minecraft:$mc")
    mappings(loom.officialMojangMappings())
    annotationProcessor("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7")
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

loom {
    mixin.useLegacyMixinAp = true
    runs.named("server") {
        server()
        runDir = "run-server"
    }
    runs.named("client") {
        client()
        runDir = "run"
        programArgs("--username", "Surveyor")
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaVer)
    targetCompatibility = JavaVersion.toVersion(javaVer)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVer
    options.encoding = "UTF-8"
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "mc" to mc,
        "name" to project.property("mod.name"),
        "description" to project.property("mod.description"),
        "author" to project.property("mod.author"),
        "homepage" to project.property("mod.homepage"),
        "fabric_loader" to project.property("deps.fabric_loader"),
        "mc_range" to ((findProperty("deps.mc_range") as String?) ?: "~$mc"),
        "java" to javaVer.toString(),
    )
    inputs.properties(props)
    exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
    // Fabric API has no chunk data attachments before 1.21: 1.20.1 keeps the road columns with the chunkdata mixins (as NeoForge/Forge do)
    if (!mc.startsWith("1.20")) exclude("countryroads.chunkdata.mixins.json")
    filesMatching("fabric.mod.json") {
        expand(props)
        if (mc.startsWith("1.20")) filter { it.replace("\"mixins\": [\"countryroads.mixins.json\"]", "\"mixins\": [\"countryroads.mixins.json\", \"countryroads.chunkdata.mixins.json\"]") }
    }
    if (mc.startsWith("1.20")) {
        // 1.20.1: plural data folders, recipe/advancement/loot formats of its own (src/legacy), an item model file instead of the items/ definition
        exclude("data/*/recipe/**", "data/*/advancement/**", "data/*/loot_table/blocks/**", "assets/*/items/**")
        filesMatching("data/*/loot_table/**") { path = path.replace("/loot_table/", "/loot_tables/") }
        from(rootProject.file("src/legacy/1.20.1/resources"))
    }
    if (mc == "1.21.1") {
        // 1.21.1: the stake's recipe/advancement formats and an item model file instead of the items/ definition (src/legacy)
        exclude("assets/*/items/**")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(rootProject.file("src/legacy/1.21.1/resources"))
    }
    if (javaVer < 21) filesMatching("*.mixins.json") { filter { it.replace("JAVA_21", "JAVA_$javaVer") } }
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE"))
}

extra["mcVersion"] = mc
apply(from = rootProject.file("../../tools/Backport/renames.gradle.kts"))

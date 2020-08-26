@file:Suppress("UnstableApiUsage")

package febb

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import metautils.asm.readToClassNode
import metautils.asm.writeTo
import metautils.util.*
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.processors.JarProcessor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.nio.file.Path
import kotlin.system.measureTimeMillis


class FebbGradle : Plugin<Project> {
    override fun apply(project: Project) {
        InitProjectContext(project).apply()
    }
}

open class FebbGradleExtension {
    var minecraftVersion: String? = null
    var yarnBuild: String? = null
    var febbBuild: String? = null
    var customAbstractionManifest: File? = null
    internal var dependenciesAdded: Boolean = false

    @JvmOverloads
    fun addDependencies(project: Project, setMappings: Boolean = true) {
        require(minecraftVersion != null && yarnBuild != null && febbBuild != null) {
            "minecraftVersion, yarnBuild and febbBuild must be set BEFORE the addDependencies() call in the febb {} block."
        }

        project.repositories.maven {
            //TODO: remove when we have jcenter
            it.name = "F2BB Maven"
            it.setUrl("https://dl.bintray.com/febb/maven")
        }

        operator fun String.invoke(notation: String) = project.dependencies.add(this, notation)

        val path = "io.github.febb:api:$minecraftVersion+$yarnBuild-$febbBuild"
        "commonMainCompileOnly"("$path:api")
        "commonMainCompileOnly"("$path:api-sources")
        "modRuntime"("$path:impl-fabric")
        "minecraft"("com.mojang:minecraft:$minecraftVersion")
        if (setMappings) "mappings"("net.fabricmc:yarn:$minecraftVersion+build.$yarnBuild:v2")

        dependenciesAdded = true
    }
}


private fun FebbGradleExtension.validateVersions() {
    require(minecraftVersion != null) { "minecraftVersion must be set in a febb {} block!" }
    require(yarnBuild != null) { "yarnBuild must be set in a febb {} block!" }
    require(febbBuild != null) { "febbBuild must be set in a febb {} block!" }
}


interface ProjectContext {
    val project: Project
}

inline fun <reified T> ProjectContext.getExtension(): T = project.extensions.getByType(T::class.java)
fun ProjectContext.getSourceSets(): SourceSetContainer =
    project.convention.getPlugin(JavaPluginConvention::class.java).sourceSets

private class InitProjectContext(override val project: Project) : ProjectContext {

    fun apply() {
        val febb = project.extensions.create("febb", FebbGradleExtension::class.java)

        addJarProcessor(febb)
        addSourceSets()

        project.afterEvaluate { AfterEvaluateContext(it, febb).afterEvaluate() }
    }

    private fun addJarProcessor(febb: FebbGradleExtension) {
        val loom = getExtension<LoomGradleExtension>()
        loom.addJarProcessor(FebbJarProcessor(project, febb))
    }

    private fun addSourceSets(): Unit = with(getSourceSets()) {
        val commonMain = create("commonMain")
        val main = getByName("main")
        val fabricMain = create("fabricMain").apply {
            compileClasspath += main.compileClasspath + commonMain.compileClasspath + commonMain.output
            runtimeClasspath += main.runtimeClasspath + commonMain.output
        }

        project.tasks.withType(Jar::class.java) {
            if (it.name == "jar") {
                it.from(commonMain.output)
                it.from(fabricMain.output)
            }
        }

        project.tasks.withType(ProcessResources::class.java) {
            if (it.name == fabricMain.processResourcesTaskName) {
                it.from(commonMain.resources.srcDirs)
            }
        }
    }
}

private class AfterEvaluateContext(override val project: Project, private val febb: FebbGradleExtension) :
    ProjectContext {
    fun afterEvaluate() {
        if (febb.customAbstractionManifest == null) {
            require(febb.dependenciesAdded) { "addDependencies(project) must be called at the end of the febb {} block!" }
        }
    }
}

@Serializable
private data class AbstractedClassInfo(val apiClassName: String, val newSignature: String)

private typealias AbstractionManifest = Map<String, AbstractedClassInfo>

private val AbstractionManifestSerializer = MapSerializer(String.serializer(), AbstractedClassInfo.serializer())

private class FebbJarProcessor(private val project: Project, private val febb: FebbGradleExtension) : JarProcessor {
    private val lastUsedDevManifest: Path = project.buildDir.resolve("febb/latest-manifest.json").toPath()

    override fun process(file: File) {
        println("Attaching f2bb interfaces")

        val devManifest = getDevManifest()
        val jar = file.toPath()

        val time = measureTimeMillis {
            jar.processJarInPlace(filter = {
                devManifest.containsKey(it.toString().removeSurrounding("/", ".class"))
            }, processor = { classNode ->
                val manifestValue = devManifest.getValue(classNode.name)
                classNode.interfaces.add(manifestValue.apiClassName)
                classNode.signature = manifestValue.newSignature
            })
        }

        saveLastUsedDevManifest()

        println("Attached interfaces in $time millis")
    }

    private fun saveLastUsedDevManifest() {
        lastUsedDevManifest.createParentDirectories()
        getDevManifestPath().copyTo(lastUsedDevManifest)
    }

    private fun getDevManifest(): AbstractionManifest {
        return parseAbstractionManifest(getDevManifestPath())
    }

    private fun getDevManifestPath(): Path {
        return febb.customAbstractionManifest?.toPath() ?: getDevManifestFromOnline()
    }

    private fun parseAbstractionManifest(path: Path) = Json
        .decodeFromString(AbstractionManifestSerializer, path.readToString())

//    private fun storeLastUsedDevManifest(devManifestPath: Path) {
//       devManifestPath.copyTo(lastUsedDevManifest)
//    }

//    private inline fun <T> Path.getF2bbVersionMarkerPath(usage: (Path) -> T) = openJar {
//        usage(it.getPath("/f2bb_version.txt"))
//    }

//    private fun Path.addF2bbVersionMarker() = with(febb) {
//        getF2bbVersionMarkerPath {
//            it.writeString(versionMarker())
//        }
//    }

    private fun FebbGradleExtension.versionMarker() = "$minecraftVersion**$yarnBuild**$febbBuild"
    // Rerun transformation if it has not been run before (last used manifest does not exist)
    // or if the transformation needs to be different (last used manifest is different from current one)
    override fun isInvalid(file: File): Boolean =
        !lastUsedDevManifest.exists()  || getDevManifestPath().readToString() != lastUsedDevManifest.readToString()
//        file.toPath().getF2bbVersionMarkerPath {
//            if (!it.exists()) return true
//            return it.readToString() != versionMarker()
//        }


    override fun setup() {

    }

    private var cachedDevManifestPath: Path? = null
    // Make sure we do download a new manifest in case the versions change
    private var cachedDevManifestVersions = febb.versionMarker()

    private fun getDevManifestFromOnline(): Path {
        // Avoiding adding the dependency multiple times
        if (cachedDevManifestPath == null || cachedDevManifestVersions != febb.versionMarker()) {
            cachedDevManifestPath = downloadDevManifest()
        }
        return cachedDevManifestPath!!
    }

    private fun downloadDevManifest(): Path = with(febb) {
        validateVersions()
        val jar = project.configurations.detachedConfiguration(
            project.dependencies.create(
                "io.github.febb:api:$minecraftVersion+$yarnBuild-$febbBuild:dev-manifest"
            )
        ).resolve().first()

        jar.toPath().openJar {
            it.getPath("/abstractionManifest.json")
        }
    }


}

private fun Path.processJarInPlace(filter: (Path) -> Boolean, processor: (ClassNode) -> Unit) {
    walkJar { classes ->
        classes.forEach { classFile ->
            if (!classFile.isDirectory()) {
                if (classFile.isClassfile() && filter(classFile)) {
                    val node = readToClassNode(classFile)
                    processor(node)
                    node.writeTo(classFile)
                }
            }
        }
    }
}
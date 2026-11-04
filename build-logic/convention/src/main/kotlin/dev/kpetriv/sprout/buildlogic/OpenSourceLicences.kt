package dev.kpetriv.sprout.buildlogic

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.attributes.Category
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The open-source licences screen's data (D-M8-9): for every variant, `generate<Variant>OpenSourceLicences` writes
 * `open_source_licences.json` into the variant's generated assets, listing each external module on that variant's
 * runtime classpath (what ships in the APK; BOMs and other platforms left out) with the licences its POM declares,
 * looked up through its parent POMs when the module's own POM declares none. No runtime dependency is added; the app
 * reads the asset ([OPEN_SOURCE_LICENCES_ASSET]).
 *
 * The POMs are resolved with detached, non-transitive configurations of `group:name:version@pom`, so they go through
 * the same repositories and dependency verification as everything else. That happens when the task's inputs are first
 * computed (with the configuration cache: when its entry is stored), and only for variants a build actually runs.
 */
internal fun Project.configureOpenSourceLicences() {
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
        val runtime = variant.runtimeConfiguration
        val root = runtime.incoming.resolutionResult.rootComponent
        val task = tasks.register<GenerateOpenSourceLicences>(
            "generate${variant.name.replaceFirstChar(Char::titlecase)}OpenSourceLicences",
        ) {
            description = "Lists the ${variant.name} runtime classpath's libraries and their POM licences."
            modules.set(root.map(::shippedModules))
            poms.from(root.map { pomFiles(shippedModules(it)) })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateOpenSourceLicences::outputDirectory)
    }
}

/** `group:name:version` of every external module in the graph except platforms (BOMs constrain, they never ship). */
private fun shippedModules(root: ResolvedComponentResult): List<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .forEach { queue += it.selected }
    }
    return seen.filter { component ->
        component.id is ModuleComponentIdentifier && component.variants.none { variant ->
            val category = variant.attributes.keySet().firstOrNull { it.name == Category.CATEGORY_ATTRIBUTE.name }
                ?.let { variant.attributes.getAttribute(it)?.toString() }
            category == Category.REGULAR_PLATFORM || category == Category.ENFORCED_PLATFORM
        }
    }.map { (it.id as ModuleComponentIdentifier).run { "$group:$module:$version" } }.distinct().sorted()
}

/** The POMs of [modules] and of all their parents, resolved like any other artifact (verified, cached). */
private fun Project.pomFiles(modules: List<String>): Set<File> {
    val files = mutableSetOf<File>()
    val requested = mutableSetOf<String>()
    var pending = modules.toSet()
    while (pending.isNotEmpty()) {
        requested += pending
        // One configuration resolves one version of a module (conflict resolution), so two versions of one parent
        // (guava-parent 26.0 and 33.x, say) go into separate batches.
        val batches = pending.groupBy { it.substringBeforeLast(":") }.values
        val resolved = (0 until batches.maxOf { it.size }).flatMap { index ->
            val batch = batches.mapNotNull { it.getOrNull(index) }.toSet()
            detachedPoms(batch).incoming.artifactView { lenient(true) }.files.files
        }
        files += resolved
        pending = resolved.mapNotNull { Pom.parse(it)?.parent }.filterNot { it in requested }.toSet()
    }
    return files
}

private fun Project.detachedPoms(coordinates: Set<String>): Configuration =
    configurations.detachedConfiguration(*coordinates.map { dependencies.create("$it@pom") }.toTypedArray()).apply {
        isTransitive = false
    }

/**
 * Writes [OPEN_SOURCE_LICENCES_ASSET]: `{"libraries":[{"name","module","version","url","licences":[{"name","url"}]}]}`.
 */
@CacheableTask
abstract class GenerateOpenSourceLicences : DefaultTask() {
    /** `group:name:version` of the shipped modules. */
    @get:Input
    abstract val modules: ListProperty<String>

    /** Their POMs and their parents' POMs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val poms: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val byCoordinates = poms.files.mapNotNull(Pom::parse).associateBy { it.coordinates }
        val libraries = modules.get().map { coordinates ->
            val (group, name, version) = coordinates.split(":")
            val pom = byCoordinates[coordinates]
            Library(
                name = pom?.name?.takeUnless { "\${" in it } ?: name,
                module = "$group:$name",
                version = version,
                url = pom?.let { lineage(it, byCoordinates).firstNotNullOfOrNull(Pom::url) },
                licences = pom?.let { lineage(it, byCoordinates).firstOrNull { p -> p.licences.isNotEmpty() } }
                    ?.licences.orEmpty(),
            )
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.module }))
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        File(directory, OPEN_SOURCE_LICENCES_ASSET).writeText(toJson(libraries))
    }

    private fun lineage(pom: Pom, byCoordinates: Map<String, Pom>): Sequence<Pom> =
        generateSequence(pom) { it.parent?.let(byCoordinates::get) }.take(MAX_PARENT_DEPTH)
}

/** The asset the app's licences screen reads. */
internal const val OPEN_SOURCE_LICENCES_ASSET = "open_source_licences.json"

private const val MAX_PARENT_DEPTH = 10

private data class Licence(val name: String, val url: String?)

private data class Library(
    val name: String,
    val module: String,
    val version: String,
    val url: String?,
    val licences: List<Licence>,
)

/** The parts of a POM the list needs; [coordinates] and [parent] are `group:name:version`. */
private data class Pom(
    val coordinates: String,
    val name: String?,
    val url: String?,
    val licences: List<Licence>,
    val parent: String?,
) {
    companion object {
        fun parse(file: File): Pom? = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val project = factory.newDocumentBuilder().parse(file).documentElement
            val parentElement = project.child("parent")
            val parent = parentElement?.let { p ->
                listOf(p.text("groupId"), p.text("artifactId"), p.text("version"))
                    .takeIf { parts -> parts.all { it != null } }?.joinToString(":")
            }
            val group = project.text("groupId") ?: parentElement?.text("groupId")
            val version = project.text("version") ?: parentElement?.text("version")
            Pom(
                coordinates = "$group:${project.text("artifactId")}:$version",
                name = project.text("name"),
                url = project.text("url")?.takeUnless { "\${" in it },
                licences = project.child("licenses")?.children("license").orEmpty().mapNotNull { licence ->
                    licence.text("name")?.let { Licence(it, licence.text("url")) }
                        ?: licence.text("url")?.let { Licence(it, it) }
                }.distinct(),
                parent = parent,
            )
        }.getOrNull()

        private fun Element.children(tag: String): List<Element> =
            (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == tag }

        private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

        private fun Element.text(tag: String): String? =
            child(tag)?.textContent?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
    }
}

private fun toJson(libraries: List<Library>): String = buildString {
    append("{\"libraries\":[\n")
    libraries.forEachIndexed { index, library ->
        if (index > 0) append(",\n")
        append("{\"name\":").append(library.name.json())
        append(",\"module\":").append(library.module.json())
        append(",\"version\":").append(library.version.json())
        append(",\"url\":").append(library.url.json())
        append(",\"licences\":[")
        append(library.licences.joinToString(",") { "{\"name\":${it.name.json()},\"url\":${it.url.json()}}" })
        append("]}")
    }
    append("\n]}\n")
}

private fun String?.json(): String = this?.let { value ->
    buildString {
        append('"')
        value.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
} ?: "null"

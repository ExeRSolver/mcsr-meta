package dev.tildejustin.mcsr_meta

import dev.tildejustin.mcsr_meta.json.*
import io.github.z4kn4fein.semver.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import java.util.*
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.io.path.*
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// val legalModsPath: Path = Path.of("C:\\Users\\justi\\IdeaProjects\\legal-mods\\legal-mods")
val legalModsPath: Path = Path.of("legal-mods/legal-mods")
val aprilFoolsModsPath: Path = Path.of("mc_af-legal-mods")
val tempDir: Path = Path.of("temp")
lateinit var nameReplacements: HashMap<String, String>
lateinit var replacementDescriptions: HashMap<String, String>
lateinit var minecraftVersions: SortedSet<String>
lateinit var modIncompatibilities: List<List<String>>
lateinit var unrecommendedMods: HashMap<String, List<String>>
lateinit var obsoleteMods: HashMap<String, List<String>>
lateinit var codeSources: HashMap<String, String>
lateinit var v2Override: List<String>
lateinit var additionalIntermediary: HashMap<String, List<Intermediary>>
lateinit var bannedModVersions: HashMap<String, Set<BannedModVersionJson>>

@OptIn(ExperimentalSerializationApi::class)
private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }

// modid -> list of conditions
lateinit var conditions: HashMap<String, MutableList<String>>

// good for testing out quick changes
const val noReload = true
val comparer: (String, String) -> Int = { o1, o2 ->
    var one: Version? = null
    var two: Version? = null
    try {
        one = Version.parse(o1, false)
    } catch (_: VersionFormatException) {
    }
    try {
        two = Version.parse(o2, false)
    } catch (_: VersionFormatException) {
    }
    if (one != null && two != null) {
        one.compareTo(two)
    } else if (one != null) {
        -1
    } else if (two != null) {
        1
    } else {
        // april fools snapshots
        o1.compareTo(o2)
    }
}

fun main() {
    var mark = TimeSource.Monotonic.markNow()
    // place to store downloaded mods
    if (!Files.exists(tempDir)) Files.createDirectory(tempDir)
    if (!noReload) {
        deleteAndRecloneLegalMods()
    }
    conditions = readConditions()
    readAdditionalData()
    println("time taken: ${mark.elapsedNow().toString(DurationUnit.SECONDS, 1)}")
    mark = TimeSource.Monotonic.markNow()
    val mods = getMods(legalModsPath.parent.toFile())

    Path.of("all_mods.json").writeText(json.encodeToString(Meta(1, mods.values.sortedBy { it.modid })) + "\n")
    println("time taken: ${mark.elapsedNow().toString(DurationUnit.SECONDS, 1)}")
}

fun getMods(repoDir: File): HashMap<String, Meta.Mod> {
    val mods = HashMap<String, Meta.Mod>()
    val git = Git.open(repoDir)
    val repo = git.repository
    val revWalk = RevWalk(repo)

    val commits = git.log().call().sortedBy { -it.commitTime }

    for (commit in commits) {
        val parent = commit.parents.firstOrNull()
        val commitLink = "https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/${commit.name}/"
        val commitTime = commit.commitTime

        if (parent != null) {
            val reader = repo.newObjectReader()
            val oldTreeIter = CanonicalTreeParser().apply { reset(reader, parent.tree) }
            val newTreeIter = CanonicalTreeParser().apply { reset(reader, commit.tree) }

            val diffFormatter = DiffFormatter(ByteArrayOutputStream()).apply {
                setRepository(repo)
            }

            val diffs = diffFormatter.scan(oldTreeIter, newTreeIter)
            for (diff in diffs) {
                if (diff.changeType == DiffEntry.ChangeType.ADD &&
                    (diff.newPath.endsWith(".json") || diff.newPath.endsWith(".jar"))) {

                    val content = readBlobAtPath(repo, commit.tree.id, diff.newPath)
                    content?.let {
                        processFile(commitLink + diff.newPath, it, commitTime, mods)
                    }
                }
            }

            diffFormatter.close()
        } else {
            val treeWalk = TreeWalk(repo).apply {
                addTree(commit.tree)
                isRecursive = true
            }
            while (treeWalk.next()) {
                val path = treeWalk.pathString
                if (path.endsWith(".json") || path.endsWith(".jar")) {
                    val loader = repo.open(treeWalk.getObjectId(0))
                    val content = loader.bytes
                    processFile(commitLink + path, content, commitTime, mods)
                }
            }
            treeWalk.close()
        }
    }

    revWalk.close()
    git.close()
    return mods
}

fun readBlobAtPath(repo: Repository, treeId: ObjectId, path: String): ByteArray? {
    val treeWalk = TreeWalk(repo).apply {
        addTree(treeId)
        isRecursive = true
        filter = PathFilter.create(path)
    }
    return if (treeWalk.next()) {
        val objectId = treeWalk.getObjectId(0)
        val loader = repo.open(objectId)
        loader.bytes
    } else null
}

fun getJarBytesFromUrl(url: String): ByteArray? {
    return try {
        val connection = URI(url).toURL().openConnection()
        connection.getInputStream().use { it.readBytes() }
    } catch (e: Exception) {
        println("Error downloading JAR from $url: ${e.message}")
        null
    }
}

fun generateModVersion(path: String, jarBytes: ByteArray, jarUrl: String, commitTime: Int, mods: HashMap<String, Meta.Mod>) {
    JarInputStream(jarBytes.inputStream()).use { jarInputStream ->
        var entry: ZipEntry? = jarInputStream.nextEntry
        while (entry != null) {
            if (entry.name == "fabric.mod.json") {
                val jsonBytes = jarInputStream.readBytes()
                val modJson = json.decodeFromString<FabricModJson>(String(jsonBytes))
                val modFolderName = Paths.get(path).parent.parent.fileName.toString()
                val mod = mods.getOrPut(modJson.id) { createMod(modJson, modFolderName) }

                val fileHash = getFileHash(jarBytes)
                val modVersion = mod.versions.find { it.fileHash == fileHash }
                if (modVersion != null) {
                    modVersion.legalTimestamp = modVersion.legalTimestamp.coerceAtMost(commitTime)
                    return
                }

                val range = createSemverRangeFromFolderName(Paths.get(path).parent.fileName.toString())
                if (v2Override.contains(modJson.id)) {
                    range.add("1.12")
                }
                val unrecommendedIntersection = unrecommendedMods[modJson.id]?.flatMap { createSemverRangeFromFolderName(it) }?.intersect(range)
                val obsoleteIntersection = obsoleteMods[modJson.id]?.flatMap { createSemverRangeFromFolderName(it) }?.intersect(range)

                mod.versions.add(Meta.ModVersion(
                    range,
                    modJson.version,
                    jarUrl,
                    getModHash(jarBytes),
                    fileHash,
                    commitTime,
                    getIllegalTimestamp(modJson.id, Paths.get(path).fileName.toString()),
                    unrecommendedIntersection?.isEmpty() ?: true,
                    obsoleteIntersection?.isNotEmpty() ?: false,
                    getBundledMods(jarBytes, modJson.jars)
                ))
                return
            }
            entry = jarInputStream.nextEntry
        }
    }
    return
}

fun getIllegalTimestamp(modId: String, jarName: String): Int {
    val bannedVersions = bannedModVersions[modId] ?: return -1
    val version = bannedVersions.find { it.jar == jarName }
    return version?.illegalTimestamp ?: -1
}

fun getBundledMods(jarBytes: ByteArray, bundledJars: List<FabricModJson.File>): List<Meta.BundledMod> {
    if (bundledJars.isEmpty()) {
        return listOf()
    }

    val bundledMods = ArrayList<Meta.BundledMod>()
    JarInputStream(jarBytes.inputStream()).use { jarInputStream ->
        var entry: ZipEntry? = jarInputStream.nextEntry
        while (entry != null) {
            if ((bundledJars.map { it.file } ).contains(entry.name)) {
                val bundledJarBytes = jarInputStream.readBytes()
                JarInputStream(bundledJarBytes.inputStream()).use { bundledJarInputStream ->
                    var bundledEntry: ZipEntry? = bundledJarInputStream.nextEntry
                    while (bundledEntry != null) {
                        if (bundledEntry.name == "fabric.mod.json") {
                            val jsonBytes = bundledJarInputStream.readBytes()
                            val modJson = json.decodeFromString<FabricModJson>(String(jsonBytes))

                            bundledMods.add(Meta.BundledMod(
                                modJson.id,
                                modJson.name,
                                modJson.description,
                                modJson.version,
                                getModHash(bundledJarBytes)
                            ))
                        }
                        bundledEntry = bundledJarInputStream.nextEntry
                    }
                }
            }
            entry = jarInputStream.nextEntry
        }
    }
    return bundledMods
}

fun getModHash(jarBytes: ByteArray): String {
    val entries = mutableListOf<Pair<String, ByteArray?>>()

    ZipInputStream(ByteArrayInputStream(jarBytes)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val name = "/${entry.name.removeSuffix("/")}"
            val content = if (entry.isDirectory) null else zip.readBytes()
            entries.add(name to content)
            entry = zip.nextEntry
        }
    }

    val digest = MessageDigest.getInstance("SHA-512")
    digest.update("/".toByteArray(Charsets.UTF_8))
    entries.sortedBy { it.first }.forEach { (name, content) ->
        digest.update(name.toByteArray(Charsets.UTF_8))
        content?.let { digest.update(it) }
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun getFileHash(jarBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-512")
    val hashBytes = digest.digest(jarBytes)
    return hashBytes.joinToString("") { "%02x".format(it) }
}

fun createMod(modJson: FabricModJson, modFolderName: String): Meta.Mod {
    modJson.description = replacementDescriptions.getOrDefault(modFolderName, modJson.description)
    modJson.name = nameReplacements.getOrDefault(modFolderName, modJson.name)
    if (codeSources[modFolderName] == null) {
        throw RuntimeException("missing source repo for $modFolderName")
    }
    return Meta.Mod(
        modJson.id,
        modJson.name,
        modJson.description,
        codeSources[modFolderName]!!,
        ArrayList(),
        conditions.getOrDefault(modFolderName, emptyList()),
        modIncompatibilities.filter { it.contains(modFolderName) }.flatten().filter { it != modFolderName },
        unrecommendedMods[modFolderName]?.isNotEmpty() ?: true,
        obsoleteMods[modFolderName]?.isEmpty() ?: false,
    )
}

fun processFile(path: String, content: ByteArray, commitTime: Int, mods: HashMap<String, Meta.Mod>) {
    if (path.endsWith(".json")) {
        if (path.endsWith("conditional-mods.json"))
            return

        try {
            val metadata = json.decodeFromString<ExternalModJson>(content.toString(Charsets.UTF_8))
            metadata.link.let { link ->
                val jarBytes = getJarBytesFromUrl(link)
                if (jarBytes != null) {
                    generateModVersion(path.removePrefix("https://"), jarBytes, link, commitTime, mods)
                } else {
                    println("Failed to read JAR from link: $link")
                }
            }
        } catch (e: Exception) {
            println("Failed to parse JSON in $path: ${e.message}")
        }
    } else {
        generateModVersion(path.removePrefix("https://"), content, path, commitTime, mods)
    }
}

@Serializable
data class AdditionalData(
    val names: HashMap<String, String>,
    val descriptions: HashMap<String, String>,
    val sources: HashMap<String, String>,
    @SerialName("max-versions") val maxVersions: List<String>,
    @SerialName("not-recommended") val notRecommended: HashMap<String, List<String>>,
    val obsolete: HashMap<String, List<String>>,
    val incompatibilities: List<List<String>>,
    @SerialName("extra-traits") val extraTraits: HashMap<String, Set<String>>,
    @SerialName("v2-override") val v2Override: List<String>,
    @SerialName("additional-intermediary") val additionalIntermediary: HashMap<String, List<Intermediary>>,
    @SerialName("banned-mods") val bannedModVersions: HashMap<String, Set<BannedModVersionJson>>
)

fun readAdditionalData() {
    val additionalMetadata = json.decodeFromString<AdditionalData>(Path.of("data.json").readText())
    val versions = additionalMetadata.maxVersions.map { maxVersion ->
        if (maxVersion.count { it == '.' } == 1) return@map listOf(maxVersion)
        val minor = maxVersion.substring(0, maxVersion.lastIndexOf("."))
        // legacy fabric only has 1.19.4, 1.10.2, 1.11.2, 1.12.2, and 1.13.2 for production intermediaries rn
        if (minor.split(".")[1].toInt() in 9..13) return@map listOf(maxVersion)
        val maxPatch = maxVersion.split(".").last().toInt()
        val intermediateVersions = (1..maxPatch).map { "$minor.$it" } as ArrayList
        intermediateVersions.addFirst(minor)
        return@map intermediateVersions
    }.flatten()
    nameReplacements = additionalMetadata.names
    replacementDescriptions = additionalMetadata.descriptions
    minecraftVersions = versions.toSortedSet(comparer)
    unrecommendedMods = additionalMetadata.notRecommended
    obsoleteMods = additionalMetadata.obsolete
    modIncompatibilities = additionalMetadata.incompatibilities
    codeSources = additionalMetadata.sources
    v2Override = additionalMetadata.v2Override
    additionalIntermediary = additionalMetadata.additionalIntermediary
    bannedModVersions = additionalMetadata.bannedModVersions
    additionalMetadata.extraTraits.forEach { (k, v) -> conditions.getOrPut(k) { ArrayList() }.addAll(v) }
}

fun readConditions(): HashMap<String, MutableList<String>> {
    val fileData = Json.decodeFromString<HashMap<String, List<String>>>(legalModsPath.parent.resolve("conditional-mods.json").readText())
    val map = HashMap<String, MutableList<String>>()
    fileData.forEach { entry ->
        entry.value.forEach {
            map.getOrPut(it) { ArrayList() }.add(entry.key)
        }
    }
    return map
}

fun createSemverRangeFromFolderName(folder: String): MutableSet<String> {
    var parts = folder.split("-")
    if (folder == "1.RV-Pre1") parts = listOf(folder)
    assert(parts.count() in 1..2)
    if ("+" in folder) {
        val minVersion = Version.parse(parts[0].replace("+", ""), false)
        return minecraftVersions.filter {
            Version.parse(it, false) >= minVersion
        }.toSortedSet(comparer)
    }
    if (parts.count() == 1) {
        // return sortedSetOf<String>(comparer, parts[0]) fails on type at runtime
        return listOf(parts[0]).toSortedSet(comparer)
    }
    val minVersion = Version.parse(parts[0], false)
    val maxVersion = Version.parse(parts[1], false)
    return minecraftVersions.filter {
        val currentVersion = Version.parse(it, false)
        return@filter currentVersion in minVersion..maxVersion
    }.toSortedSet(comparer)
}

// clear old repo and re-clone it
fun deleteAndRecloneLegalMods() {
    Path.of("legal-mods").toFile().deleteRecursively()
    Path.of("mc_af-legal-mods").toFile().deleteRecursively()
    Git.cloneRepository().setURI("https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods").setProgressMonitor(TextProgressMonitor()).call()
    Git.cloneRepository().setURI("https://github.com/tildejustin/mc_af-legal-mods").setProgressMonitor(TextProgressMonitor()).call()
}

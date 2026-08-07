package com.pankaj.mlbbdraft.engine.data

import com.pankaj.mlbbdraft.engine.model.DatasetManifest
import com.pankaj.mlbbdraft.engine.model.BuildFile
import com.pankaj.mlbbdraft.engine.model.HeroFile
import com.pankaj.mlbbdraft.engine.model.ItemFile
import com.pankaj.mlbbdraft.engine.model.MatchupFile
import kotlinx.serialization.json.Json

/**
 * Loads the bundled dataset from java resources.
 *
 * Resources (rather than Android assets) so the same loader works in JVM unit tests
 * and in the app — AGP packages a JVM module's resources into the APK.
 */
object DatasetLoader {
    const val MANIFEST_PATH: String = "data/manifest.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun fromResources(
        classLoader: ClassLoader = DatasetLoader::class.java.classLoader
            ?: error("No class loader available to read the dataset"),
    ): HeroDatabase {
        val manifest = json.decodeFromString<DatasetManifest>(read(classLoader, MANIFEST_PATH))
        val base = MANIFEST_PATH.substringBeforeLast('/', "")
        fun resolve(path: String) = if (base.isEmpty()) path else "$base/$path"

        val heroes = manifest.heroFiles.flatMap { path ->
            json.decodeFromString<HeroFile>(read(classLoader, resolve(path))).heroes
        }
        val matchups = manifest.matchupFiles.map { path ->
            json.decodeFromString<MatchupFile>(read(classLoader, resolve(path)))
        }
        val items = manifest.itemFiles.flatMap { path ->
            json.decodeFromString<ItemFile>(read(classLoader, resolve(path))).items
        }
        val coreBuilds = manifest.buildFiles.fold(emptyMap<String, List<String>>()) { acc, path ->
            acc + json.decodeFromString<BuildFile>(read(classLoader, resolve(path))).builds
        }

        return HeroDatabase(
            patch = manifest.patch,
            heroes = heroes,
            counters = matchups.flatMap { it.counters },
            synergies = matchups.flatMap { it.synergies },
            items = items,
            coreBuilds = coreBuilds,
        )
    }

    /** For tests and for user-supplied dataset overrides. */
    fun fromContents(
        patch: String,
        heroFileContents: List<String>,
        matchupFileContents: List<String> = emptyList(),
    ): HeroDatabase {
        val matchups = matchupFileContents.map { json.decodeFromString<MatchupFile>(it) }
        return HeroDatabase(
            patch = patch,
            heroes = heroFileContents.flatMap { json.decodeFromString<HeroFile>(it).heroes },
            counters = matchups.flatMap { it.counters },
            synergies = matchups.flatMap { it.synergies },
        )
    }

    private fun read(classLoader: ClassLoader, path: String): String =
        classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Dataset resource '$path' not found on the classpath")
}

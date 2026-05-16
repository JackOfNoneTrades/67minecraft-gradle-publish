import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

val sixtySevenUnimixinsProjectId = "kzM9rC6D"
val forced67MinecraftVersion = providers.gradleProperty("67minecraftVersionNumber")
	.orNull
	?.trim()
	?.takeIf { it.isNotEmpty() }
if (forced67MinecraftVersion != null) {
	project.version = forced67MinecraftVersion
	extensions.extraProperties.set("modVersion", forced67MinecraftVersion)
}

data class SixtySevenDependency(
	val dependencyType: String,
	val projectId: String? = null,
	val versionId: String? = null,
)

data class SixtySevenUploadFile(
	val partName: String,
	val file: File,
)

fun prop(name: String): String? =
	providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun env(name: String): String? =
	providers.environmentVariable(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun splitList(value: String?): List<String> =
	value
		?.split(",", " ", "\n", "\t")
		?.map { it.trim() }
		?.filter { it.isNotEmpty() }
		?: emptyList()

fun boolProp(name: String, default: Boolean): Boolean =
	prop(name)?.equals("true", ignoreCase = true) ?: default

fun boolEnv(name: String, default: Boolean): Boolean =
	env(name)?.equals("true", ignoreCase = true) ?: default

fun githubRefType(): String? =
	env("GITHUB_REF_TYPE") ?: env("GITHUB_REF")?.substringAfter("refs/", "")?.substringBefore("/")

fun shouldPublishOnCurrentRef(): Boolean {
	if (!boolEnv("GITHUB_ACTIONS", false)) {
		return true
	}

	if (githubRefType()?.equals("tag", ignoreCase = true) == true) {
		return true
	}

	return boolProp("67minecraftAllowNonTagPublish", false)
}

fun jsonString(value: String): String =
	buildString {
		append('"')
		for (char in value) {
			when (char) {
				'\\' -> append("\\\\")
				'"' -> append("\\\"")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				else -> append(char)
			}
		}
		append('"')
	}

fun jsonValue(value: Any?): String =
	when (value) {
		null -> "null"
		is String -> jsonString(value)
		is Boolean -> value.toString()
		is Number -> value.toString()
		is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
			jsonString(it.key.toString()) + ":" + jsonValue(it.value)
		}
		is Iterable<*> -> value.joinToString(",", "[", "]") { jsonValue(it) }
		else -> jsonString(value.toString())
	}

fun parseRelations(raw: String?): MutableList<SixtySevenDependency> {
	val dependencies = mutableListOf<SixtySevenDependency>()

	raw
		?.split(";")
		?.map { it.trim() }
		?.filter { it.isNotEmpty() }
		?.forEach { relation ->
			val scopeAndRest = relation.split("-", limit = 2)
			val kindAndId = scopeAndRest.getOrNull(1)?.split(":", limit = 2)

			if (scopeAndRest.size != 2 || kindAndId == null || kindAndId.size != 2) {
				throw GradleException("Invalid 67minecraftRelations entry: $relation")
			}

			val scope = scopeAndRest[0]
			val kind = kindAndId[0]
			val id = kindAndId[1]

			if (scope !in setOf("required", "optional", "incompatible", "embedded")) {
				throw GradleException("Invalid 67minecraft relation scope: $scope")
			}

			dependencies += when (kind) {
				"project" -> SixtySevenDependency(scope, projectId = id)
				"version" -> SixtySevenDependency(scope, versionId = id)
				else -> throw GradleException("Invalid 67minecraft relation kind: $kind")
			}
		}

	return dependencies
}

fun selectedArtifactTaskName(): String =
	prop("67minecraftArtifactTask")
		?: listOf("shadowJar", "remapJar", "jar").firstOrNull { tasks.findByName(it) != null }
		?: "jar"

fun archiveFileFrom(value: Any?, label: String): File? {
	val resolved = when (value) {
		is TaskProvider<*> -> value.get()
		is Provider<*> -> value.orNull
		else -> value
	}

	return when (resolved) {
		null -> null
		is AbstractArchiveTask -> resolved.archiveFile.get().asFile
		is RegularFile -> resolved.asFile
		is File -> resolved
		else -> throw GradleException("$label is not an archive task or file: ${resolved::class.qualifiedName}")
	}
}

fun extraArchiveFileOrNull(name: String): File? {
	val extras = extensions.extraProperties
	if (!extras.has(name)) {
		return null
	}

	return archiveFileFrom(extras.get(name), name)
}

fun taskArchiveFileOrNull(name: String): File? {
	val task = tasks.findByName(name) ?: return null
	return archiveFileFrom(task, name)
}

fun selectedArtifactFile(taskName: String): File {
	prop("67minecraftFile")?.let { return file(it) }

	val task = tasks.named(taskName).get()
	return archiveFileFrom(task, taskName)
		?: throw GradleException("Task $taskName did not resolve to an archive file.")
}

fun primaryArtifactFile(): File {
	prop("67minecraftFile")?.let { return file(it) }
	return extraArchiveFileOrNull("publishableObfJar") ?: selectedArtifactFile(selectedArtifactTaskName())
}

fun additionalArtifactFiles(primary: File): List<File> {
	val configuredFiles = splitList(prop("67minecraftAdditionalFiles")).map { file(it) }
	if (configuredFiles.isNotEmpty()) {
		return configuredFiles
	}

	val files = mutableListOf<File>()

	if (boolProp("67minecraftIncludeDevJar", true)) {
		extraArchiveFileOrNull("publishableDevJar")?.let { files += it }
	}

	val noPublishedSources = prop("noPublishedSources")?.equals("true", ignoreCase = true) == true
	if (boolProp("67minecraftIncludeSources", !noPublishedSources)) {
		taskArchiveFileOrNull("sourcesJar")?.let { files += it }
	}

	if (boolProp("67minecraftIncludeApiJar", true)) {
		extraArchiveFileOrNull("publishableApiJar")?.let { files += it }
	}

	return files
		.filter { it.absoluteFile != primary.absoluteFile }
		.distinctBy { it.absoluteFile.normalize().path }
}

fun gtnhModVersion(): String? {
	val extras = extensions.extraProperties
	return if (extras.has("modVersion")) {
		extras.get("modVersion")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
	} else {
		null
	}
}

fun readChangelog(versionNumber: String): String {
	env("CHANGELOG_FILE")?.let {
		val configured = file(it)
		if (configured.isFile) return configured.readText().trim()
	}

	prop("67minecraftChangelogFile")?.let {
		val configured = file(it)
		if (configured.isFile) return configured.readText().trim()
	}

	val candidates = listOf(
		file(".changelogs/$versionNumber.md"),
		file("CHANGELOG.md"),
		file("changelog.md"),
	)

	return candidates.firstOrNull { it.isFile }?.readText()?.trim()
		?: "No changelog was specified."
}

fun writeText(out: java.io.OutputStream, value: String) {
	out.write(value.toByteArray(StandardCharsets.UTF_8))
}

fun encodePathSegment(value: String): String =
	URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

fun unescapeJsonString(value: String): String =
	buildString {
		var index = 0
		while (index < value.length) {
			val char = value[index]
			if (char != '\\' || index + 1 >= value.length) {
				append(char)
				index++
				continue
			}

			when (val escaped = value[index + 1]) {
				'"', '\\', '/' -> append(escaped)
				'b' -> append('\b')
				'f' -> append('\u000c')
				'n' -> append('\n')
				'r' -> append('\r')
				't' -> append('\t')
				'u' -> {
					val hex = value.substring(index + 2, index + 6)
					append(hex.toInt(16).toChar())
					index += 4
				}
				else -> append(escaped)
			}

			index += 2
		}
	}

fun jsonStringField(json: String, field: String): String? {
	val match = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(json)
		?: return null

	return unescapeJsonString(match.groupValues[1])
}

fun resolveProjectId(apiBase: String, reference: String): String {
	val connection = URI("${apiBase.trimEnd('/')}/project/${encodePathSegment(reference)}")
		.toURL()
		.openConnection() as HttpURLConnection

	connection.requestMethod = "GET"
	connection.setRequestProperty("User-Agent", "67minecraft-gradle-publish")
	connection.setRequestProperty("Accept", "application/json")

	val code = connection.responseCode
	val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
		?.bufferedReader()
		?.readText()
		.orEmpty()

	if (code !in 200..299) {
		throw GradleException("Failed to resolve 67minecraft project reference '$reference' with HTTP $code:\n$response")
	}

	val id = jsonStringField(response, "id")
		?: throw GradleException("Failed to resolve 67minecraft project reference '$reference': response did not include an id.")

	if (id != reference) {
		println("Resolved 67minecraft project reference '$reference' to '$id'.")
	}

	return id
}

fun inferredVersionType(versionNumber: String): String =
	prop("67minecraftVersionType") ?: when {
		boolEnv("SNAPSHOT", false) || versionNumber.endsWith("-snapshot", ignoreCase = true) -> "beta"
		versionNumber.endsWith("-pre", ignoreCase = true) -> "beta"
		else -> "release"
	}

fun uploadVersion(
	apiBase: String,
	token: String,
	dataJson: String,
	files: List<SixtySevenUploadFile>,
) {
	val boundary = "----67minecraft-${UUID.randomUUID()}"
	val connection = URI("${apiBase.trimEnd('/')}/version").toURL().openConnection() as HttpURLConnection

	connection.requestMethod = "POST"
	connection.doOutput = true
	connection.setRequestProperty("Authorization", token)
	connection.setRequestProperty("User-Agent", "67minecraft-gradle-publish")
	connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

	connection.outputStream.use { out ->
		writeText(out, "--$boundary\r\n")
		writeText(out, "Content-Disposition: form-data; name=\"data\"\r\n")
		writeText(out, "Content-Type: application/json\r\n\r\n")
		writeText(out, dataJson)
		writeText(out, "\r\n")

		for (uploadFile in files) {
			writeText(out, "--$boundary\r\n")
			writeText(
				out,
				"Content-Disposition: form-data; name=\"${uploadFile.partName}\"; filename=\"${uploadFile.file.name}\"\r\n",
			)
			writeText(out, "Content-Type: application/java-archive\r\n\r\n")
			uploadFile.file.inputStream().use { it.copyTo(out) }
			writeText(out, "\r\n")
		}

		writeText(out, "--$boundary--\r\n")
	}

	val code = connection.responseCode
	val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
		?.bufferedReader()
		?.readText()
		.orEmpty()

	if (code !in 200..299) {
		throw GradleException("67minecraft upload failed with HTTP $code:\n$response")
	}

	println("67minecraft upload succeeded: $response")
}

val publish67Minecraft = tasks.register("publish67Minecraft") {
	group = "publishing"
	description = "Publishes this mod version to 67minecraft."
	notCompatibleWithConfigurationCache("Uploads files from a remote script using project state at execution time.")

	doLast {
		if (!shouldPublishOnCurrentRef()) {
			println(
				"Skipping 67minecraft publish on GitHub ref type '${githubRefType() ?: "unknown"}'. " +
					"Set -P67minecraftAllowNonTagPublish=true to override.",
			)
			return@doLast
		}

		val projectId = prop("67minecraftProjectId")
			?: throw GradleException("Missing gradle.properties value: 67minecraftProjectId")
		val token = env("SIX_SEVEN_TOKEN")
			?: throw GradleException("Missing environment variable: SIX_SEVEN_TOKEN")

		val primaryArtifact = primaryArtifactFile()
		if (!primaryArtifact.isFile) {
			throw GradleException("67minecraft primary artifact does not exist: ${primaryArtifact.absolutePath}")
		}

		val additionalArtifacts = additionalArtifactFiles(primaryArtifact)
		for (artifact in additionalArtifacts) {
			if (!artifact.isFile) {
				throw GradleException("67minecraft additional artifact does not exist: ${artifact.absolutePath}")
			}
		}

		val versionNumber = prop("67minecraftVersionNumber") ?: gtnhModVersion() ?: project.version.toString()
		val versionName = prop("67minecraftVersionName") ?: versionNumber
		val apiBase = prop("67minecraftApiUrl") ?: "https://67.fentanylsolutions.org/api/v2"
		val loaders = splitList(prop("67minecraftLoaders")).ifEmpty { listOf("forge") }
		val gameVersions = splitList(prop("67minecraftGameVersions")).ifEmpty {
			listOf(prop("minecraftVersion") ?: "1.7.10")
		}

		val dependencies = parseRelations(prop("67minecraftRelations"))
		val usesMixins = prop("usesMixins")?.equals("true", ignoreCase = true) == true

		if (usesMixins && dependencies.none { it.projectId == sixtySevenUnimixinsProjectId }) {
			dependencies += SixtySevenDependency("required", projectId = sixtySevenUnimixinsProjectId)
		}

		val resolvedProjectIds = mutableMapOf<String, String>()
		fun resolvedProjectId(reference: String): String =
			resolvedProjectIds.getOrPut(reference) { resolveProjectId(apiBase, reference) }

		val dependencyJson = dependencies.map {
			buildMap<String, Any> {
				put("dependency_type", it.dependencyType)
				it.projectId?.let { projectId -> put("project_id", resolvedProjectId(projectId)) }
				it.versionId?.let { versionId -> put("version_id", versionId) }
			}
		}

		val uploadFiles = listOf(SixtySevenUploadFile("file", primaryArtifact)) +
			additionalArtifacts.mapIndexed { index, artifact ->
				SixtySevenUploadFile("file_${index + 1}", artifact)
			}
		val fileParts = uploadFiles.map { it.partName }

		val data = mapOf(
			"project_id" to projectId,
			"file_parts" to fileParts,
			"primary_file" to "file",
			"version_number" to versionNumber,
			"version_title" to versionName,
			"version_body" to readChangelog(versionNumber),
			"dependencies" to dependencyJson,
			"game_versions" to gameVersions,
			"version_type" to inferredVersionType(versionNumber),
			"loaders" to loaders,
			"featured" to false,
			"status" to (prop("67minecraftStatus") ?: "listed"),
		)

		println("67minecraft primary file: ${primaryArtifact.name}")
		if (additionalArtifacts.isNotEmpty()) {
			println("67minecraft additional files: ${additionalArtifacts.joinToString { it.name }}")
		}

		uploadVersion(apiBase, token, jsonValue(data), uploadFiles)
	}
}

afterEvaluate {
	publish67Minecraft.configure {
		val explicitTask = prop("67minecraftArtifactTask")
		when {
			explicitTask != null -> dependsOn(explicitTask)
			tasks.findByName("build") != null -> dependsOn("build")
			else -> dependsOn(selectedArtifactTaskName())
		}
	}
}

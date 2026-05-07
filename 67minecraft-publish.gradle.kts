import org.gradle.api.GradleException
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

val sixtySevenUnimixinsProjectId = "kzM9rC6D"

data class SixtySevenDependency(
      val dependencyType: String,
      val projectId: String? = null,
      val versionId: String? = null,
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

fun selectedArtifactFile(taskName: String): File {
      prop("67minecraftFile")?.let { return file(it) }

      val task = tasks.named(taskName).get()
      val archiveTask = task as? AbstractArchiveTask
              ?: throw GradleException("Task $taskName is not an archive task. Set 67minecraftFile manually.")

      return archiveTask.archiveFile.get().asFile
}

fun readChangelog(versionNumber: String): String {
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

fun uploadVersion(
      apiBase: String,
      token: String,
      dataJson: String,
      filePartName: String,
      artifact: File,
) {
      val boundary = "----67minecraft-${UUID.randomUUID()}"
      val connection = URL("${apiBase.trimEnd('/')}/version").openConnection() as HttpURLConnection

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

              writeText(out, "--$boundary\r\n")
              writeText(
                      out,
                      "Content-Disposition: form-data; name=\"$filePartName\"; filename=\"${artifact.name}\"\r\n",
              )
              writeText(out, "Content-Type: application/java-archive\r\n\r\n")
              artifact.inputStream().use { it.copyTo(out) }
              writeText(out, "\r\n--$boundary--\r\n")
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

      doLast {
              val projectId = prop("67minecraftProjectId")
                      ?: throw GradleException("Missing gradle.properties value: 67minecraftProjectId")
              val token = env("SIX_SEVEN_TOKEN")
                      ?: throw GradleException("Missing environment variable: SIX_SEVEN_TOKEN")

              val taskName = selectedArtifactTaskName()
              val artifact = selectedArtifactFile(taskName)
              if (!artifact.isFile) {
                      throw GradleException("67minecraft artifact does not exist: ${artifact.absolutePath}")
              }

              val versionNumber = prop("67minecraftVersionNumber") ?: project.version.toString()
              val versionName = prop("67minecraftVersionName") ?: versionNumber
              val apiBase = prop("67minecraftApiUrl") ?: "https://67.fentanylsolutions.org/api/v2"
              val loaders = splitList(prop("67minecraftLoaders")).ifEmpty { listOf("forge") }
              val gameVersions = splitList(prop("67minecraftGameVersions")).ifEmpty {
                      listOf(prop("minecraftVersion") ?: "1.7.10")
              }

              val dependencies = parseRelations(prop("67minecraftRelations"))
              val usesMixins = prop("usesMixins")?.equals("true", ignoreCase = true) == true

              if (usesMixins) {
                    if (dependencies.none { it.projectId == sixtySevenUnimixinsProjectId }) {
                            dependencies += SixtySevenDependency("required", projectId = sixtySevenUnimixinsProjectId)
                    }
              }

              val dependencyJson = dependencies.map {
                      buildMap<String, Any> {
                              put("dependency_type", it.dependencyType)
                              it.projectId?.let { projectId -> put("project_id", projectId) }
                              it.versionId?.let { versionId -> put("version_id", versionId) }
                      }
              }

              val filePartName = "file"
              val data = mapOf(
                      "project_id" to projectId,
                      "file_parts" to listOf(filePartName),
                      "primary_file" to filePartName,
                      "version_number" to versionNumber,
                      "version_title" to versionName,
                      "version_body" to readChangelog(versionNumber),
                      "dependencies" to dependencyJson,
                      "game_versions" to gameVersions,
                      "version_type" to (prop("67minecraftVersionType") ?: "release"),
                      "loaders" to loaders,
                      "featured" to false,
                      "status" to (prop("67minecraftStatus") ?: "listed"),
              )

              uploadVersion(apiBase, token, jsonValue(data), filePartName, artifact)
      }
}

afterEvaluate {
      publish67Minecraft.configure {
              dependsOn(selectedArtifactTaskName())
      }
}

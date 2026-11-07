package io.github.fei2411.dsbot.locallinux

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import kotlin.concurrent.thread

class LocalLinuxModule : Module() {
  private var process: Process? = null
  private var output = ""

  override fun definition() = ModuleDefinition {
    Name("LocalLinux")

    AsyncFunction("getStatus") {
      val ctx = appContext.reactContext ?: throw Exception("Android context unavailable")
      status(ctx)
    }

    AsyncFunction("installRuntime") { url: String, expectedSha256: String ->
      val ctx = appContext.reactContext ?: throw Exception("Android context unavailable")
      require(url.startsWith("https://")) { "Runtime URL must use HTTPS" }
      require(expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Invalid SHA-256" }
      val base = File(ctx.filesDir, "linux")
      val archive = File(ctx.cacheDir, "linux-runtime.tar.gz.part")
      try { downloadRuntimeArchive(url, archive) } catch (e: Exception) { archive.delete(); throw e }
      val digest = MessageDigest.getInstance("SHA-256")
      FileInputStream(archive).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
      }
      val actual = digest.digest().joinToString("") { "%02x".format(it) }
      if (!actual.equals(expectedSha256, true)) { archive.delete(); throw Exception("Runtime checksum mismatch") }
      val staging = File(ctx.filesDir, "linux.installing")
      staging.deleteRecursively(); staging.mkdirs()
      try {
        var expandedBytes = 0L
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(FileInputStream(archive))).use { tar ->
          while (true) {
            val entry = tar.nextEntry ?: break
            entries++
            if (entries > MAX_ARCHIVE_ENTRIES) throw Exception("Runtime archive contains too many files")
            val relative = entry.name.removePrefix("./").trimEnd('/')
            if (relative.isBlank() || relative == "." || relative.startsWith("/") || relative.split('/').any { it == ".." }) {
              throw Exception("Unsafe archive path")
            }
            val lexical = File(staging, relative).absoluteFile.toPath().normalize().toFile()
            require(lexical.path.startsWith(staging.absolutePath + File.separator)) { "Unsafe archive path" }
            val target = lexical
            target.parentFile?.mkdirs()
            when {
              entry.isDirectory -> target.mkdirs()
              entry.isSymbolicLink -> {
                val link = entry.linkName
                val root = File(staging, "rootfs").absoluteFile.toPath().normalize().toFile()
                val linkTarget = if (link.startsWith("/")) {
                  require(relative == "rootfs" || relative.startsWith("rootfs/")) { "Absolute symlink outside rootfs" }
                  File(root, link.removePrefix("/")).absoluteFile.toPath().normalize().toFile()
                } else File(target.parentFile, link).absoluteFile.toPath().normalize().toFile()
                val allowedRoot = if (relative == "rootfs" || relative.startsWith("rootfs/")) root else staging.absoluteFile.toPath().normalize().toFile()
                require(linkTarget.path == allowedRoot.path || linkTarget.path.startsWith(allowedRoot.path + File.separator)) { "Symlink escapes runtime root" }
                val relativeLink = target.parentFile.toPath().relativize(linkTarget.toPath()).toString()
                android.system.Os.symlink(relativeLink, target.path)
              }
              entry.isLink -> {
                val rawLink = entry.linkName.removePrefix("./")
                val root = File(staging, "rootfs").absoluteFile.toPath().normalize().toFile()
                val linkTarget = if (rawLink.startsWith("/")) File(root, rawLink.removePrefix("/")).absoluteFile.toPath().normalize().toFile() else File(staging, rawLink).absoluteFile.toPath().normalize().toFile()
                require(linkTarget.path.startsWith(root.path + File.separator) && linkTarget.exists()) { "Invalid hard link" }
                android.system.Os.link(linkTarget.path, target.path)
              }
              entry.isFile -> {
                FileOutputStream(target).use { out ->
                  val buffer = ByteArray(64 * 1024)
                  while (true) {
                    val count = tar.read(buffer)
                    if (count < 0) break
                    expandedBytes += count
                    if (expandedBytes > MAX_EXPANDED_BYTES) throw Exception("Expanded runtime exceeds the 4 GiB limit")
                    out.write(buffer, 0, count)
                  }
                }
                android.system.Os.chmod(target.path, entry.mode and 0b111111111)
              }
            }
          }
        }
        val manifest = File(staging, "runtime.json")
        val prootFile = File(staging, "bin/proot")
        val nodeFile = File(staging, "bin/node")
        val loaderFile = File(staging, "libexec/proot/loader")
        val rootfs = File(staging, "rootfs")
        if (!prootFile.isFile || !nodeFile.isFile || !loaderFile.isFile || !File(staging, "lib/libtalloc.so").exists() || !rootfs.isDirectory || !manifest.isFile ||
          !File(rootfs, "usr/local/bin/node").exists() || !File(rootfs, "usr/local/bin/dsh").exists()) {
          throw Exception("Runtime archive is incomplete: expected bin/proot, bin/node, libexec/proot/loader, lib/libtalloc.so, rootfs/usr/local/bin/node, rootfs/usr/local/bin/dsh and runtime.json")
        }
        val manifestJson = JSONObject(manifest.readText())
        val expectedAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (manifestJson.optString("abi") != expectedAbi) {
          throw Exception("Runtime ABI does not match this device ($expectedAbi)")
        }
        prootFile.setExecutable(true, true)
        nodeFile.setExecutable(true, true)
        loaderFile.setExecutable(true, true)
        val backup = File(ctx.filesDir, "linux.previous")
        backup.deleteRecursively()
        if (base.exists() && !base.renameTo(backup)) throw Exception("Could not move the previous runtime")
        if (!staging.renameTo(base)) {
          if (backup.exists()) backup.renameTo(base)
          throw Exception("Could not activate runtime")
        }
        backup.deleteRecursively()
      } catch (e: Exception) { staging.deleteRecursively(); throw e }
      finally { archive.delete() }
      status(ctx)
    }

    AsyncFunction("startBot") { apiKey: String ->
      require(apiKey.isNotBlank()) { "DeepSeek API key is required" }
      val ctx = appContext.reactContext ?: throw Exception("Android context unavailable")
      val base = File(ctx.filesDir, "linux")
      val proot = File(base, "bin/proot")
      val rootfs = File(base, "rootfs")
      val data = File(ctx.filesDir, "dsh-data")
      require(File(base, "runtime.json").exists()) { "Linux runtime is not installed" }
      require(File(base, "libexec/proot/loader").isFile) { "PRoot loader is missing from runtime" }
      require(process?.isAlive != true) { "Bot is already running" }
      if (!data.exists() && !data.mkdirs()) throw Exception("Could not create persistent DSH data directory")
      val workDir = File(rootfs, "workspace")
      if (!workDir.exists() && !workDir.mkdirs()) throw Exception("Could not create Linux working directory")
      val cmd = listOf(
        proot.absolutePath, "-0", "-r", rootfs.absolutePath,
        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", data.absolutePath + ":/data",
        "-w", "/workspace", "/usr/bin/env", "HOME=/home/node", "DSH_HOME=/data",
        "DSH_PERMISSION_MODE=danger-full-access", "PATH=/usr/local/bin:/usr/bin:/bin",
        "/bin/sh", "-lc",
        "if [ ! -d /data/profiles/web ]; then mkdir -p /data && cp -a /opt/dsh-template/. /data/; fi; mkdir -p /workspace; export HOME=/home/node; cd /workspace; exec /usr/local/bin/dsh --profile web --patch /opt/ds-bot/docker.patch.yml --no-open --host 127.0.0.1 --port 3080"
      )
      val launched = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).apply {
        val env = environment()
        env["LD_LIBRARY_PATH"] = File(base, "lib").absolutePath
        env["PROOT_LOADER"] = File(base, "libexec/proot/loader").absolutePath
        env["PROOT_TMP_DIR"] = File(ctx.cacheDir, "proot-tmp").absolutePath
        env["DEEPSEEK_API_KEY"] = apiKey
      }.start()
      process = launched
      output = "Bot process started (pid ${launched.pid()})"
      thread(isDaemon = true) {
        launched.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line -> synchronized(this@LocalLinuxModule) { output = (output + "\n" + line).takeLast(12000) } }
        }
      }
      thread(isDaemon = true) {
        val exitCode = launched.waitFor()
        synchronized(this@LocalLinuxModule) {
          if (process === launched) process = null
          output = (output + "\nBot process exited: $exitCode").takeLast(12000)
        }
      }
      mapOf("running" to launched.isAlive, "pid" to launched.pid())
    }

    AsyncFunction("stopBot") {
      val current = process
      current?.destroy()
      if (current?.isAlive == true) {
        current.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
      }
      if (current?.isAlive == true) current.destroyForcibly()
      if (process === current) process = null
      mapOf("running" to false)
    }
  }

  private companion object {
    const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_ARCHIVE_ENTRIES = 100000
  }


  private fun downloadRuntimeArchive(initialUrl: String, archive: File) {
    var current = URL(initialUrl)
    repeat(6) { hop ->
      require(current.protocol.equals("https", true)) { "Runtime redirect must remain HTTPS" }
      val connection = (current.openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 60000
        instanceFollowRedirects = false
      }
      try {
        connection.connect()
        val code = connection.responseCode
        if (code in 300..399) {
          if (hop == 5) throw Exception("Too many runtime download redirects")
          val location = connection.getHeaderField("Location") ?: throw Exception("Runtime redirect has no Location")
          current = URL(current, location)
          require(current.protocol.equals("https", true)) { "Runtime redirect must remain HTTPS" }
          return@repeat
        }
        if (code !in 200..299) throw Exception("Download failed: HTTP $code")
        if (connection.contentLengthLong > MAX_ARCHIVE_BYTES) throw Exception("Runtime archive exceeds the 2 GiB limit")
        connection.inputStream.use { input -> FileOutputStream(archive).use { output ->
          val buffer = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ARCHIVE_BYTES) throw Exception("Runtime archive exceeds the 2 GiB limit")
            output.write(buffer, 0, count)
          }
        } }
        return
      } finally { connection.disconnect() }
    }
    if (!archive.isFile || archive.length() == 0L) throw Exception("Runtime download did not complete")
  }

  private fun status(ctx: android.content.Context): Map<String, Any> {
    val home = File(ctx.filesDir, "linux")
    return mapOf(
      "installed" to File(home, "runtime.json").exists(),
      "running" to (process?.isAlive == true),
      "directory" to home.absolutePath,
      "abi" to android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
      "lastOutput" to output.takeLast(4000)
    )
  }
}

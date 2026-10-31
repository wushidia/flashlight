package io.custom.flashlight

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal data class ShellResult(val exitCode: Int, val output: String)

/** Drain stdout while waiting, bound retained output, and terminate unresponsive root requests. */
internal object ShellRunner {
    fun run(command: List<String>, script: String, timeoutMillis: Long = 15_000): ShellResult {
        var process: Process? = null
        return try {
            val child = ProcessBuilder(command).redirectErrorStream(true).start()
            process = child
            val output = FutureTask {
                child.inputStream.bufferedReader().use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val keep = minOf(count, (65_536 - result.length).coerceAtLeast(0))
                        if (keep > 0) result.append(buffer, 0, keep)
                    }
                    result.toString().trim()
                }
            }
            Thread(output, "torch-output").apply { isDaemon = true; start() }
            val execution = FutureTask {
                child.outputStream.bufferedWriter().use { it.write(script) }
                child.waitFor()
            }
            Thread(execution, "torch-command").apply { isDaemon = true; start() }
            try {
                val exitCode = execution.get(timeoutMillis, TimeUnit.MILLISECONDS)
                ShellResult(exitCode, output.get(1000, TimeUnit.MILLISECONDS))
            } catch (_: java.util.concurrent.TimeoutException) {
                child.destroy()
                execution.cancel(true)
                output.cancel(true)
                ShellResult(-1, "Root command timed out")
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ShellResult(-1, error.message.orEmpty())
        } finally {
            process?.destroy()
        }
    }
}

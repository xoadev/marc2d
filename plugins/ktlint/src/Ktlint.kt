package marc2d.ktlint

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Kotlin Toolchain has no linter: this runs the ktlint CLI, with the Compose rules on its
 * classpath so that ServiceLoader picks them up. The format comes from the root `.editorconfig`.
 *
 * It receives the module root, so that tests are checked too, and not `ModuleSources`, which in 0.13
 * arrives empty in Android modules. It fails if the path does not exist: a linter that finds no code
 * must not give its approval.
 */
@TaskAction
fun ktlint(
	@Input sourceDir: Path,
	@Input tool: Classpath,
	format: Boolean,
) {
	check(sourceDir.isDirectory()) { "ktlint: $sourceDir does not exist" }

	val java = File(System.getProperty("java.home"), "bin/java").path
	val command =
		buildList {
			add(java)
			// ktlint uses reflection on the JDK.
			add("--add-opens=java.base/java.lang=ALL-UNNAMED")
			add("-cp")
			add(tool.resolvedFiles.joinToString(File.pathSeparator))
			add("com.pinterest.ktlint.Main")
			if (format) add("--format")
			add("$sourceDir/**/*.kt")
		}
	val exit =
		ProcessBuilder(command)
			.inheritIO()
			.start()
			.waitFor()
	check(exit == 0) { "ktlint found errors" }
}

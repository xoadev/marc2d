package marc2d.ktlint

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * El Kotlin Toolchain no trae linter: se ejecuta la CLI de ktlint, con las reglas de Compose en su
 * classpath para que las cargue el ServiceLoader. El formato sale del `.editorconfig` de la raíz.
 *
 * Recibe la ruta y no `ModuleSources`, que en la 0.13 llega vacío en módulos Android. Falla si la ruta
 * no existe: un linter que no encuentra código no puede dar el visto bueno.
 */
@TaskAction
fun ktlint(
	@Input sourceDir: Path,
	@Input tool: Classpath,
	format: Boolean,
) {
	check(sourceDir.isDirectory()) { "ktlint: no existe $sourceDir" }

	val java = File(System.getProperty("java.home"), "bin/java").path
	val command =
		buildList {
			add(java)
			// ktlint usa reflexión sobre el JDK.
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
	check(exit == 0) { "ktlint ha encontrado errores" }
}

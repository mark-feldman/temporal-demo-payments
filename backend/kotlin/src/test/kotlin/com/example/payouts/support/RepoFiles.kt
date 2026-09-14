package com.example.payouts.support

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Locates a file that ships with the repository but is not on the classpath -- `app.js`, the
 * Prometheus config. A Gradle Test task runs in `backend/kotlin`, but an IDE run configuration
 * may run from the repository root, so try every spelling at each level on the way up rather
 * than trusting the working directory.
 */
fun repoFile(vararg candidates: String): Path {
    val relative = candidates.map(Path::of)
    var dir: Path? = Path.of("").toAbsolutePath()
    while (dir != null) {
        relative.map(dir::resolve).firstOrNull(Files::exists)?.let { return it }
        dir = dir.parent
    }
    fail("could not find any of ${candidates.toList()} from ${Path.of("").toAbsolutePath()}")
}

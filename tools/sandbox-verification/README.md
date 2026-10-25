# Sandbox verification helpers (NOT part of the mod or its Gradle build)

The environment this project was written in had **no network access**, so ForgeGradle could not download
Minecraft/Forge and `gradlew build` could not be run there. To still execute the unit tests of the
Minecraft-independent core (`com.questjournal.core.*`) the following helpers were used:

* `junit-shim/` - a ~100 line stand-in for the small subset of the JUnit 5 API the tests use
  (`@Test`, `@BeforeEach`, `@TempDir`, `Assertions`) plus a reflection based runner.
* `run-core-tests.sh` - compiles `src/main/java/com/questjournal/core` and `src/test/java` against the shim and runs them.

You do **not** need any of this: with network access `./gradlew test` runs the very same test sources with the real
JUnit 5. These files only document how the numbers in `docs/BUILD_AND_TEST_REPORT.md` were obtained.

plugins {
    alias(libs.plugins.sprout.jvm.library)
    // FakeClock lives in testFixtures so JVM and Android modules can share it in tests.
    `java-test-fixtures`
}

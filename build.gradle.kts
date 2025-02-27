plugins { id("io.vacco.oss.gitflow") version "0.9.8" }

group = "io.vacco.coroutines"
version = "0.10.0"

configure<io.vacco.oss.gitflow.GsPluginProfileExtension> {
  addClasspathHell()
  sharedLibrary(true, false)
}

configure<JavaPluginExtension> {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

val api by configurations

dependencies {
  testImplementation("junit:junit:4.13.2")
}

tasks.withType<Javadoc> {
  isFailOnError = false
}

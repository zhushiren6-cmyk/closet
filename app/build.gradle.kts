plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Copies web/ (minus tests) into generated assets as assets/web, stamping the version like the Pages deploy. */
abstract class CopyWeb : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val src: DirectoryProperty
    @get:Input abstract val version: Property<String>
    @get:OutputDirectory abstract val out: DirectoryProperty

    @TaskAction fun run() {
        val root = out.get().asFile
        root.deleteRecursively()
        val dest = File(root, "web")
        src.get().asFile.copyRecursively(dest)
        File(dest, "test").deleteRecursively()
        val app = File(dest, "app.js")
        val before = app.readText()
        val after = before.replace("const VERSION = 'dev'", "const VERSION = '${version.get()}'")
        check(after != before) { "VERSION marker not found in web/app.js" }
        app.writeText(after)
    }
}

val copyWeb = tasks.register<CopyWeb>("copyWeb") {
    src.set(rootProject.layout.projectDirectory.dir("web"))
    version.set("android " + (System.getenv("GITHUB_SHA") ?: "dev").take(7))
}

androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(copyWeb, CopyWeb::out) }
}

android {
    namespace = "com.felix.closet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.felix.closet"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        getByName("debug") {
            // Fixed key committed to the repo so each new build installs over the previous one.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
}

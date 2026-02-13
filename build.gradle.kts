var version_major: String by extra
var version_minor: String by extra
var version_patch: String by extra

var project_group: String by extra
var project_fqn:   String by extra
var project_name:  String by extra

var ceres_version: String by extra
var sedna_version: String by extra
var minux_version: String by extra

// Repositories for dependencies.
var ceres_repo: String by extra
var sedna_repo: String by extra
var minux_repo: String by extra

plugins {
	id("java")
	id("maven-publish")
	id("application")

	id("net.minecraftforge.gradle") version "[7.0.2,8.0)"
}

minecraft {
	mappings("official", "1.20.1")

	runs {
		configureEach {
			systemProperty("forge.logging.markers", "REGISTRIES")
			systemProperty("forge.logging.console.level", "debug")
		}
	}
}

fun getGitRef(): String {
	try {
		var stdout = providers.exec {
			commandLine("git", "rev-parse", "--short", "HEAD")
		}.standardOutput.asText.get()

		return stdout.toString().trim()
	} catch (ignored: Throwable) {
		return "unknown"
	}
}

fun getBuildRef(): String {
	if (System.getenv("PROMOTED_NUMBER") != null) {
		return "${System.getenv("PROMOTED_NUMBER")}"
	} else if (System.getenv("BUILD_NUMBER") != null) {
		return "${System.getenv("BUILD_NUMBER")}"
	} else {
		return "+" + getGitRef()
	}
}

var build_ref: String = getBuildRef()

var semver: String  = "${version_major}.${version_minor}.${version_patch}"

version           = "${semver}+${build_ref}"
group             = "${project_group}"
base.archivesName = "${project_name}"

java.toolchain {
	languageVersion = JavaLanguageVersion.of(21)
}

repositories {
	minecraft.mavenizer(this)
	maven(fg.forgeMaven)
	maven(fg.minecraftLibsMaven)
	maven("https://maven.minecraftforge.net/")

	exclusiveContent {
		forRepository {
			maven {
				name = "Sponge"
				url = uri("https://repo.spongepowered.org/repository/maven-public")
			}
		}
		filter {
			includeGroupAndSubgroups("org.spongepowered")
		}
	}
	mavenLocal()

	maven("https://cursemaven.com")
	mavenCentral()
	listOf("${sedna_repo}", "${minux_repo}", "${ceres_repo}").forEach{repo ->
		maven {
			url = uri("https://maven.pkg.github.com/${repo}")
			credentials {
				username = project.findProperty("gpr.user") as String? ?: System.getenv("GPR_USER")
				password = project.findProperty("gpr.key") as String? ?: System.getenv("GPR_KEY")
			}
		}
	} 
}

fun tryProject(projectName: String, libraryName: String): Any {
	return findProject(projectName) ?: libraryName
}

dependencies {
	implementation(minecraft.dependency("net.minecraftforge:forge:1.20.1-47.4.10"))
	implementation("com.google.guava:guava:16+")
	implementation("commons-io:commons-io:2.11.0")
	implementation("it.unimi.dsi:fastutil:8.5.8")
	implementation("org.apache.commons:commons-lang3:3.12.0")
	implementation("org.apache.logging.log4j:log4j-api:2.17.0")
	implementation("org.apache.logging.log4j:log4j-core:2.17.0")
	implementation("org.ow2.asm:asm-commons:9.2")
	implementation("org.ow2.asm:asm:9.1")

	implementation("org.jline:jline:3.20.0")
	implementation("org.jline:jline-terminal-jna:3.20.0")
	implementation("net.java.dev.jna:jna:5.9.0")

	implementation(
		tryProject(":modules:ceres", "li.cil.ceres:ceres:${ceres_version}")
	)
	implementation(
		tryProject(":modules:sedna", "li.cil.sedna:sedna:${sedna_version}")
	)
	implementation(
		tryProject(":modules:sedna-buildroot", "li.cil.sedna:sedna-buildroot:${minux_version}")
	)
	implementation(
		tryProject(":modules:oc2r", "curse.maven:oc2r-1037738:6280699")
	)

	testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
}

publishing {
	publications {
		register<MavenPublication>("mavenJava"){
			artifactId = "jar"
		}
	}
	repositories {
		maven {
			url = uri(System.getenv("MAVEN_PATH"))
		}
	}
}

application {
	mainClass = "${project_fqn}.Main"
}

tasks.getByName("run", JavaExec::class){
	standardInput  = System.`in`
	standardOutput = System.`out`
}

tasks.named<Test>("test") {
	useJUnitPlatform()
}


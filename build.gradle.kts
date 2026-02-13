import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.io.File
import java.util.regex.Pattern

// For downloading and checksumming files.
import java.net.URL
import java.net.URI
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.io.FileOutputStream
import java.security.MessageDigest
import java.math.BigInteger



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

// For firmware.
var opensbi_repo    = extra["opensbi_repo"]
var opensbi_hash    = extra["opensbi_hash"]
var opensbi_version = extra["opensbi_version"]

var fw_jump_hash:    String by extra
var fw_dynamic_hash: String by extra

// List of output files obtained during reobfJar.
var output_list: MutableList<String> = mutableListOf<String>()

plugins {
	id("java")
	id("maven-publish")
	id("application")

	id("io.freefair.compress.trees") version "9.2.0"
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

// TODO: Move sha512sum and DownloadFile to their own file? sub-project?
fun sha512sum(filepath: String, comparison_hash: String): Boolean {
	try {
		val sha512: MessageDigest = MessageDigest.getInstance("SHA-512")

		val checked_file: File = File(filepath)
		val digested: ByteArray = sha512.digest(Files.readAllBytes(checked_file.toPath()))
		val hash: String = String.format("%0128x", BigInteger(1, digested))

		if( hash == comparison_hash ) {
			return true
		} else {
			throw GradleException(String.format("sha512sum: ERROR: \"%s\" doesn't match hash of \"%s\n", hash, comparison_hash))
			return false
		}
	} catch (thrown: Throwable) {
		System.out.println(thrown.toString())
		return false
	}
}

fun DownloadFile(fileurl: String, output: String): Boolean {
	try {
		// Read from URL into a temporary file.
		val src_url: URL = URI.create(fileurl).toURL()
		val byte_channel: ReadableByteChannel = Channels.newChannel(src_url.openStream())

		val outstream: FileOutputStream = FileOutputStream(output)
		outstream.getChannel().transferFrom(byte_channel, 0, Long.MAX_VALUE)
	} catch (thrown: Throwable) {
		System.out.println(thrown.toString())
		return false
	}


	if (File("${output}").isFile()) {
		return true
	} else {
		return false
	}
}

tasks.register("OpenSBI") {
	val opensbi_filename: String = "opensbi-${opensbi_version}-rv-bin.tar.xz"
	val opensbi_url:      String = "https://github.com/${opensbi_repo}/releases/download/v${opensbi_version}/${opensbi_filename}"

	// tmp that exist during OpenSBI.
	val tmp_dir:     String = getTemporaryDir().toString()
	val tarball:     String = "${tmp_dir}/${opensbi_filename}"

	// Where we want to put everything.
	val build_dir:   String = project.layout.buildDirectory.get().toString()
	val out_dir:     String = "${build_dir}/resources/main/assets/opensbi"

	// Attempt download.
	if( DownloadFile(opensbi_url, tarball) == false ){
		throw GradleException(String.format("ERROR: Unable to download file from \"%s\" to \"%s\".\n", opensbi_url, tarball))
	}

	if( sha512sum(tarball, "${opensbi_hash}") == true ){
		// Extract the OpenSBI tarball.
		copy {
			from(commonsCompress.tarXzTree(tarball))

			// Just extract certain .bin files, don't keep directories.
			include("**/lp64/generic/firmware/fw_jump.bin")
			include("**/lp64/generic/firmware/fw_dynamic.bin")
			eachFile {
				relativePath = RelativePath(true, *relativePath.segments.drop(6).toTypedArray())
			}
			includeEmptyDirs = false

			into(tmp_dir)
		}

		// If the .bin file sums succeed.
		if( sha512sum("${tmp_dir}/fw_jump.bin", fw_jump_hash) && sha512sum("${tmp_dir}/fw_dynamic.bin", fw_dynamic_hash) ) {
			copy {
				from(fileTree(tmp_dir))

				include("**/fw_jump.bin")
				include("**/fw_dynamic.bin")

				into("${out_dir}")
			}
		} else {
			throw GradleException("ERROR: opensbi .bin files don't match their hashes.")
		}
	} else {
		throw GradleException("ERROR: opensbi tarball doesn't match it's hash.")
	}
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

tasks.build {
	dependsOn("OpenSBI")
	doFirst{tasks.getByName("OpenSBI")}
}

tasks.jar {
	from("resources/main/assets/opensbi") {
		include("fw_jump.bin");
		include("fw_dynamic.bin")
	}
}

tasks.getByName("run", JavaExec::class){
	standardInput  = System.`in`
	standardOutput = System.`out`
}

tasks.named<Test>("test") {
	useJUnitPlatform()
}


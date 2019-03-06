@file:Suppress("unused") // usages in build scripts are not tracked properly

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.kotlin.dsl.extra
import java.io.File

fun RepositoryHandler.androidDxJarRepo(project: Project): IvyArtifactRepository = ivy {
    val baseDir = File("${project.rootDir}/dependencies/repo")
    ivyPattern("${baseDir.canonicalPath}/[organisation]/[module]/[revision]/[module].ivy.xml")
    artifactPattern("${baseDir.canonicalPath}/[organisation]/[module]/[revision]/[artifact](-[classifier]).jar")
    artifactPattern("${baseDir.canonicalPath}/[organisation]/sources/[artifact]-[revision](-[classifier]).[ext]")
}

fun Project.androidDxJar() = "kotlin.build:android-dx:${rootProject.extra["versions.androidBuildTools"]}"

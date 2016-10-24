import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import org.gradle.api.plugins.*
import org.gradle.script.lang.kotlin.*

buildscript {
    extra["kotlinVersion"] = "1.1-M02"
    extra["kotlin-eap-repo"] = "https://dl.bintray.com/kotlin/kotlin-eap-1.1"
    extra["gradle-repo"] = "https://repo.gradle.org/gradle/repo"
    extra["ecl-repo"] = "https://repo.eclipse.org/content/groups/releases/"
    extra["tmate-repo"] = "http://maven.tmatesoft.com/content/repositories/releases/"

    repositories {
        listOf("kotlin-eap-repo", "gradle-repo").forEach {
            maven {
                setUrl(extra[it])
            }
        }
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${extra["kotlinVersion"]}")
        classpath("com.github.jengelman.gradle.plugins:shadow:1.2.3")
    }
}

apply {
    plugin("kotlin")
    plugin<ApplicationPlugin>()
    plugin<ShadowPlugin>()
}

configure<ApplicationPluginConvention> {
    mainClassName = "net.berndhaug.gitExternals.GitExternalsKt"
}

repositories {
    mavenLocal()
    jcenter()
    mavenCentral()
    listOf("kotlin-eap-repo", "ecl-repo", "tmate-repo").forEach {
        maven {
            setUrl(extra[it])
        }
    }
}

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib:${extra["kotlinVersion"]}")
    testCompile("junit:junit:4.12")
    compile("org.tmatesoft.svnkit:svnkit:1.8.12")
    compile("org.eclipse.jgit:org.eclipse.jgit:4.4.1.201607150455-r")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.7.5")
//    compile("me.sargunvohra.lib.CakeParse:1.1.1")
}

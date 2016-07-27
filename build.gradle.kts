import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowSpec
import org.gradle.api.plugins.*
import org.gradle.script.lang.kotlin.*

buildscript {
    extra["kotlinVersion"] = "1.0.3"
    extra["gradle-repo"] = "https://repo.gradle.org/gradle/repo"
    extra["ecl-repo"] = "https://repo.eclipse.org/content/groups/releases/"
    extra["tmate-repo"] = "http://maven.tmatesoft.com/content/repositories/releases/"

    repositories {
        maven { setUrl(extra["gradle-repo"]) }
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
    maven {
        mavenLocal()
        setUrl(extra["tmate-repo"])
        setUrl(extra["ecl-repo"])
        jcenter()
        mavenCentral()
    }
}

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib:${extra["kotlinVersion"]}")
    compile("org.tmatesoft.svnkit:svnkit:1.8.12")
    compile("org.eclipse.jgit:org.eclipse.jgit:4.4.1.201607150455-r")
}

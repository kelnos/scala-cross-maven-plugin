package org.spurint.maven.plugins

import java.io.{File, FileInputStream, FileOutputStream}
import java.util
import java.util.Properties
import org.apache.maven.model._
import org.apache.maven.model.io.xpp3.{MavenXpp3Reader, MavenXpp3Writer}
import org.apache.maven.plugin.{AbstractMojo, MojoExecutionException, MojoFailureException}
import org.apache.maven.plugins.annotations.{LifecyclePhase, Mojo, Parameter}
import org.apache.maven.project.MavenProject
import scala.collection.JavaConverters._
import scala.language.reflectiveCalls
import scala.util.control.NonFatal

object RewritePomMojo {
  private val PROP_PATTERN = """[^\\]\$\{([^\}]+)\}""".r
}

@Mojo(name = "rewrite-pom", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
class RewritePomMojo extends AbstractMojo {
  import RewritePomMojo._

  @Parameter(property = "rewrittenPomPath", defaultValue = "${project.build.directory}/.scala-cross-pom.xml")
  private var rewrittenPomPath: File = _

  @Parameter(property = "scalaProfilePrefix", defaultValue = "scala-")
  private var scalaProfilePrefix: String = "scala-"

  @Parameter(property = "scalaProfileId")
  private var scalaProfileId: String = _

  @Parameter(property = "scrubProfiles", defaultValue = "false")
  private var scrubProfiles: Boolean = false

  @Parameter(defaultValue = "${project}", required = true, readonly = false)
  private var project: MavenProject = _

  override def execute(): Unit = {
    val model = loadModelFromScratch(this.project.getModel)

    val scalaProfileId = Option(this.scalaProfileId)
      .orElse(guessProfileId(this.project))
      .getOrElse(
        throw new MojoExecutionException("Unable to determine scala profile; try setting -DscalaProfilePrefix or -DscalaProfileId")
      )
    val scalaProfile = findProfile(scalaProfileId).getOrElse(
      throw new MojoExecutionException(s"Profile with ID '$scalaProfileId' does not exist")
    )
    getLog.info(s"Using profile '${scalaProfile.getId}'")

    val scalaProperties = scalaProfile.getProperties

    mergeSections(model, scalaProfile)
    interpolateProperties(model, scalaProperties)

    if (this.scrubProfiles) {
      Option(this.scalaProfileId).map(id =>
        model.getProfiles.asScala.filter(_.getId == id).foreach(model.removeProfile)
      ).orElse(
        Option(this.scalaProfilePrefix).map(_ =>
          model.getProfiles.asScala.filter(_.getId.startsWith(this.scalaProfilePrefix)).foreach(model.removeProfile)
        )
      )
    }

    Option(this.rewrittenPomPath.getParentFile).foreach(_.mkdirs())
    val output = new FileOutputStream(this.rewrittenPomPath)
    try {
      new MavenXpp3Writer().write(output, model)
    } catch {
      case NonFatal(e) =>
        this.rewrittenPomPath.delete()
        throw new MojoFailureException(e.getMessage, e)
    } finally {
      output.close()
    }

    model.setPomFile(this.rewrittenPomPath)
    this.project.setFile(this.rewrittenPomPath)
  }

  // The Model in `this.project.getModel` has the parent's content merged
  // into it already.  Since we want to write a new POM that's structurally
  // similar to the original POM, we reload it first.
  private def loadModelFromScratch(model: Model): Model = {
    val input = new FileInputStream(model.getPomFile)
    try {
      new MavenXpp3Reader().read(input)
    } finally {
      input.close()
    }
  }

  private def guessProfileId(project: MavenProject): Option[String] = {
    project.getActiveProfiles.asInstanceOf[util.List[Profile]].asScala
      .find(_.getId.startsWith(this.scalaProfilePrefix))
      .map(_.getId)
      .orElse(Option(project.getParent).flatMap(guessProfileId))
  }


  private def findProfile(id: String): Option[Profile] = {
    val ourProfile = findProfile(this.project, id)
    val parentProfiles = accumulateParentProfiles(this.project, id)
    (ourProfile.toList ++ parentProfiles).foldLeft(Option.empty[Profile])({
      case (Some(accum), next) => Some(mergeSections(accum, next.clone()))
      case (None, next) => Some(next.clone())
    })
  }

  private def accumulateParentProfiles(project: MavenProject, id: String): List[Profile] = {
    Option(project.getParent).fold(List.empty[Profile])({
      parent =>
        val parentProfile = findProfile(parent, id).map(_.clone())
        parentProfile.foreach({ pp =>
          // These need to be unset because they shouldn't be emitted into the flattened POM
          pp.setBuild(null)
          pp.setDependencies(null)
          pp.setDependencyManagement(null)
          pp.setModules(null)
          pp.setReporting(null)
        })
        parentProfile.toList ++ accumulateParentProfiles(parent, id)
    })
  }

  private def findProfile(project: MavenProject, id: String): Option[Profile] =
    project.getModel.getProfiles.asScala.find(_.getId == id)

  private type ProfileOrModel = {
    def getClass(): Class[_]
    def addModule(module: String): Unit
    def addProperty(key: String, value: String): Unit
    def setDistributionManagement(distMgmt: DistributionManagement): Unit
    def addRepository(repo: Repository): Unit
    def addPluginRepository(repo: Repository): Unit
    def getDependencyManagement(): DependencyManagement
    def setDependencyManagement(depMgmt: DependencyManagement): Unit
    def addDependency(dep: Dependency): Unit
    def getReporting(): Reporting
    def setReporting(reporting: Reporting): Unit
  }

  private def mergeSections[T <: ProfileOrModel](pom: T, profile: Profile): T = {
    profile.getModules.asScala.foreach(pom.addModule)
    profile.setModules(null)

    profile.getProperties.asScala.foreach({ case (k, v) => pom.addProperty(k, v) })
    profile.setProperties(null)

    Option(profile.getDistributionManagement).foreach({ distmgmt =>
      pom.setDistributionManagement(distmgmt)
      profile.setDistributionManagement(null)
    })

    profile.getRepositories.asScala.foreach(pom.addRepository)
    profile.setRepositories(null)
    profile.getPluginRepositories.asScala.foreach(pom.addPluginRepository)
    profile.setPluginRepositories(null)

    Option(profile.getDependencyManagement).foreach({ depMgmt =>
      Option(pom.getDependencyManagement()).fold(
        pom.setDependencyManagement(depMgmt)
      )(
        modelDepMgmt => depMgmt.getDependencies.asScala.foreach(modelDepMgmt.addDependency)
      )
      profile.setDependencyManagement(null)
    })

    profile.getDependencies.asScala.foreach(pom.addDependency)
    profile.setDependencies(null)

    Option(profile.getReporting).foreach({ profileReporting =>
      Option(pom.getReporting()).fold(
        pom.setReporting(profileReporting)
      )({ modelReporting =>
        Option(profileReporting.getExcludeDefaults).foreach({ defaults =>
          modelReporting.setExcludeDefaults(defaults)
          profileReporting.setExcludeDefaults(null)
        })

        Option(profileReporting.getOutputDirectory).foreach({ dir =>
          modelReporting.setOutputDirectory(dir)
          profileReporting.setOutputDirectory(null)
        })

        profileReporting.getPlugins.asScala.foreach(modelReporting.addPlugin)
        profileReporting.setPlugins(null)
      })
    })

    Option(profile.getBuild).foreach({ profileBuild =>
      // Annoyingly necessary because Model returns a Build and Profile
      // returns a BuildBase, but scala strucutral types don't seem to
      // be up to the task of allowing me to use type bounds in a useful
      // way here.
      val modelBuild = pom match {
        case m: Model => Option(m.getBuild).getOrElse({ m.setBuild(new Build); m.getBuild })
        case p: Profile => Option(p.getBuild).getOrElse({ p.setBuild(new Build); p.getBuild })
        case x => throw new AssertionError(s"BUG: Unexpected type ${x.getClass().getName} passsed to mergeSections()")
      }

      Option(profileBuild.getPluginManagement).foreach({ pluginMgmt =>
        val modelPluginMgmt = Option(modelBuild.getPluginManagement).getOrElse({
          modelBuild.setPluginManagement(new PluginManagement)
          modelBuild.getPluginManagement
        })
        pluginMgmt.getPlugins.asScala.foreach(modelPluginMgmt.addPlugin)
        profileBuild.setPluginManagement(null)
      })

      profileBuild.getPlugins.asScala.foreach(modelBuild.addPlugin)
      profileBuild.setPlugins(null)

      Option(profileBuild.getFinalName).foreach({ name =>
        modelBuild.setFinalName(name)
        profileBuild.setFinalName(null)
      })

      Option(profileBuild.getDefaultGoal).foreach({ goal =>
        modelBuild.setDefaultGoal(goal)
        profileBuild.setDefaultGoal(null)
      })

      Option(profileBuild.getDirectory).foreach({ dir =>
        modelBuild.setDirectory(dir)
        profileBuild.setDirectory(null)
      })

      profileBuild.getResources.asScala.foreach(modelBuild.addResource)
      profileBuild.setResources(null)
      profileBuild.getTestResources.asScala.foreach(modelBuild.addTestResource)
      profileBuild.setTestResources(null)

      profileBuild.getFilters.asScala.foreach(modelBuild.addFilter)
      profileBuild.setFilters(null)
    })

    pom
  }

  private def interpolateProperties(model: Model, properties: Properties): Unit = {
    Option(model.getParent).foreach({ parent =>
      parent.setGroupId(interpolate(properties, parent.getGroupId))
      parent.setArtifactId(interpolate(properties, parent.getArtifactId))
    })
    model.setGroupId(interpolate(properties, model.getGroupId))
    model.setArtifactId(interpolate(properties, model.getArtifactId))

    val dependencies =
      Option(model.getDependencyManagement).fold(List.empty[Dependency])(_.getDependencies.asScala.toList) ++
        model.getDependencies.asScala
    dependencies.foreach({ dependency =>
      dependency.setGroupId(interpolate(properties, dependency.getGroupId))
      dependency.setArtifactId(interpolate(properties, dependency.getArtifactId))
    })

    val plugins =
      Option(model.getBuild).flatMap(b => Option(b.getPluginManagement)).fold(List.empty[Plugin])(_.getPlugins.asScala.toList) ++
        Option(model.getBuild).fold(List.empty[Plugin])(_.getPlugins.asScala.toList)
    plugins.foreach({ plugin =>
      plugin.setGroupId(interpolate(properties, plugin.getGroupId))
      plugin.setArtifactId(interpolate(properties, plugin.getArtifactId))
    })
  }

  private def propertiesInValue(value: String): List[String] = {
    Option(value).fold(List.empty[String])(v => PROP_PATTERN.findAllMatchIn(v).toList.map(_.group(1)))
  }

  private def interpolate(properties: Properties, value: String): String =
    propertiesInValue(value).foldLeft(value)({
      case (accum, next) if properties.getProperty(next) != null =>
        accum.replaceFirst(s"""\\$$\\{${next.replaceAllLiterally(".", "\\.")}\\}""", properties.getProperty(next))
      case (accum, _) =>
        accum
    })
}

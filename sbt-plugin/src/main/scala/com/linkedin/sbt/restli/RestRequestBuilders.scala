package com.linkedin.sbt.restli

import sbt._
import sbt.Keys._
import com.linkedin.restli.internal.common.RestliVersion
import com.linkedin.restli.tools.clientgen.RestRequestBuilderGenerator
import scala.collection.JavaConverters._

/**
 * Provides java client binding generation.
 * @param project
 */
class RestRequestBuilderGeneratorProject(val project : Project) extends RestRequestBuilders with Pegasus {

  import RequestBuildersKeys._

  def generateRequestBuilders(dataTemplateProject: Project) = {
    project
      .settings(restliRequestBuildersArtifacts : _*)
      .settings(
        // Don't scala-version name-mangle this project, since it is intended equally for Java use
        // and does not contain Scala-generated bytecode.
        crossPaths := false,
        autoScalaLibrary := false,

        restliRequestBuildersResolverPath := (restliPegasusResolverPath in Compile in dataTemplateProject).value,
        restliRequestBuildersGenerateDatatemplates := false,
        restliRequestBuildersIdlPublishedJsonDir := sourceDirectory.value / "idl",
        restliRequestBuildersSnapshotPublishedJsonDir := sourceDirectory.value / "snapshot",

        restliRequestBuildersIdlJsonInfoCache := streams.value.cacheDirectory / "idlgen.jsonfiles",
        restliRequestBuildersSnapshotJsonInfoCache := streams.value.cacheDirectory / "snapshot.jsonfiles",

        restliRequestBuildersGeneratedJavaDir := (sourceManaged in Compile).value / "restspec_java",

        restliRequestBuildersGenerate in Compile := restliRequestBuildersGenerator.value,

        sourceGenerators in Compile <+= (restliRequestBuildersGenerate in Compile).task,

        // Hook up the restspec generation to this new PreRequestBuilders task to guarantee it's initialized
        // regardless of sbt's initialization ordering (https://github.com/sbt/sbt/issues/2090)
        (restliRequestBuildersGenerate in Compile) <<= (restliRequestBuildersGenerate in Compile) dependsOn restliGenerateRestSpecs,
        restliGenerateRestSpecs <<= restliGenerateRestSpecs or Def.task {()}
      )
    }
  }

object RequestBuildersKeys {
  val restliRequestBuildersGenerate = taskKey[Seq[File]]("Generates class files based on restspec files.")
  val restliRequestBuildersIdlPublishedJsonDir = settingKey[File]("The dir of JSON files published by the restli idl publisher, this is where the checked-in idl should reside")
  val restliRequestBuildersSnapshotPublishedJsonDir = settingKey[File]("The dir of JSON files published by the restli snapshot publisher, this is where the checked-in snapshots should reside")
  val restliRequestBuildersIdlJsonInfoCache = taskKey[File]("File for caching info about generated JSON idl files")
  val restliRequestBuildersSnapshotJsonInfoCache = taskKey[File]("File for caching info about generated JSON snapshot files")
  val restliRequestBuildersGeneratedJavaDir = settingKey[File]("The root dir (without package path) of java files generated by RestRequestBuilderGenerator")
  val restliRequestBuildersResolverPath = taskKey[String]("List of places to look for pdsc files. Seperated by ':'")
  val restliRequestBuildersGenerateDatatemplates = settingKey[Boolean]("Sets generator.rest.generate.datatemplates")
  val restliRequestBuildersPackageRestClient = taskKey[File]("Produces a rest client jar containing Builder and restspec json files")
  val restliRequestBuildersPackageRestModel = taskKey[File]("Produces a rest model jar containing only restspec json files")
  private[restli] val restliGenerateRestSpecs = taskKey[Unit]("Task to ensure restspecs are generated before generating request builders")
}

trait RestRequestBuilders extends Restli {
  import RequestBuildersKeys._

  //transforms a Project to a RestspecProject if needed, i.e. when you call a method that exists only on RestspecProject
  implicit def projectToRestRequestBuilderGeneratorProject(p : Project) = new RestRequestBuilderGeneratorProject(p)

  val DefaultConfig = config("default").extend(Runtime).describedAs("Configuration for default artifacts.")

  def restliRequestBuildersArtifacts = {
    def packageRestModelMappings = restliRequestBuildersIdlPublishedJsonDir.map( (d) => mappings(d, RestspecJsonFileGlobExpr) )

    val restClientConfig = new Configuration("restClient", "rest.li client bindings",
      isPublic = true,
      extendsConfigs = List(Compile),
      transitive = true)

    Defaults.packageTaskSettings(restliRequestBuildersPackageRestModel, packageRestModelMappings) ++
      restliArtifactSettings(restliRequestBuildersPackageRestModel)("restModel") ++
      Seq(
        packagedArtifacts <++= Classpaths.packaged(Seq(restliRequestBuildersPackageRestModel)),
        artifacts <++= Classpaths.artifactDefs(Seq(restliRequestBuildersPackageRestModel)),
        ivyConfigurations ++= List(restClientConfig, DefaultConfig),
        artifact in (Compile, packageBin) ~= { (art: Artifact) =>
          art.copy(configurations = art.configurations ++ List(restClientConfig))
        }
      )
  }

  /**
   * Runs `com.linkedin.restli.tools.clientgen.RestRequestBuilderGenerator` on the output of
   * `restliResourceModelExport in Compile` and returns a `Seq[java.io.File]` of the generated Java files.
   * If the output of the export task is `None`, this task just returns the existing Java files.
   */
  val restliRequestBuildersGenerator = Def.task {
    val s = streams.value
    val snapshotJsonDir = restliRequestBuildersSnapshotPublishedJsonDir.value
    val idlJsonDir = restliRequestBuildersIdlPublishedJsonDir.value
    val generatedJavaDir = restliRequestBuildersGeneratedJavaDir.value
    val generateDataTemplates = restliRequestBuildersGenerateDatatemplates.value
    val idlJsonInfoCache = restliRequestBuildersIdlJsonInfoCache.value
    val snapshotJsonInfoCache = restliRequestBuildersSnapshotJsonInfoCache.value
    val resolverPath = restliRequestBuildersResolverPath.value

    s.log.debug("snapshotJsonDir: " + snapshotJsonDir)
    s.log.debug("idlJsonDir: " + idlJsonDir)
    val snapshotJsonFiles = (snapshotJsonDir ** SnapshotJsonFileGlobExpr).get
    val idlJsonFiles = (idlJsonDir ** RestspecJsonFileGlobExpr).get

    val (anyIdlChanged, idlUpdateCache) = prepareCacheUpdate(idlJsonInfoCache, idlJsonFiles, s)
    val (anySnapshotsChanged, snapshotUpdateCache) = prepareCacheUpdate(snapshotJsonInfoCache, snapshotJsonFiles, s)

    if (anyIdlChanged || anySnapshotsChanged) {
      val previousJavaFiles = (generatedJavaDir ** JavaFileGlobExpr).get

      s.log.debug("generating java files based on restspec files: " + idlJsonFiles.toList.toString)

      val previousDataTemplatesSystemProperty = Option(System.getProperty("generator.rest.generate.datatemplates"))
      val previousPackageSystemProperty = Option(System.getProperty("generator.default.package"))

      System.setProperty("generator.rest.generate.datatemplates", generateDataTemplates.toString)
      System.setProperty("generator.default.package", "")

      generatedJavaDir.mkdirs()
      val generatedBuilder = {
        try {
          RestRequestBuilderGenerator.run(resolverPath, null, null, false, RestliVersion.RESTLI_2_0_0, null,
            generatedJavaDir.getAbsolutePath, idlJsonFiles.map(_.getAbsolutePath).toArray)
        } catch {
          case e: Throwable =>
            s.log.error("Error in RestRequestBuilderGenerator: " + e.toString)
            s.log.error("Resolver Path: " + resolverPath)
            s.log.error("IDL JSON files: " + idlJsonFiles)
            s.log.error("Generated Java dir: " + generatedJavaDir)
            s.log.error("Generate data templates: " + generateDataTemplates)
            throw e
        } finally {
          previousDataTemplatesSystemProperty.foreach(System.setProperty("generator.rest.generate.datatemplates", _))
          previousPackageSystemProperty.foreach(System.setProperty("generator.default.package", _))
        }
      }
      val generatedJavaFiles = generatedBuilder.getModifiedFiles.asScala.toSeq ++ generatedBuilder.getTargetFiles.asScala.toSeq
      s.log.debug("generated java files: " + generatedJavaFiles.toList.toString)

      //cleanup stale java files
      val staleFiles = previousJavaFiles.sorted.diff(generatedJavaFiles.sorted)
      s.log.debug("deleting stale files (" + staleFiles.size + "): " + staleFiles)
      IO.delete(staleFiles)

      idlUpdateCache() //we only update the cache when we get here, which means we are successful
      snapshotUpdateCache()
    }
    (generatedJavaDir ** JavaFileGlobExpr).get
  }
}

package com.typesafe.sbt.traceur

import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.{CompileProblems, LineBasedProblem}
import sbt.Keys._
import sbt._
import xsbti.Severity

object Import {

  val traceur = TaskKey[Seq[File]]("traceur", "Run Traceur compiler")

  object TraceurKeys {

    val sourceFileNames = SettingKey[Seq[String]]("traceur-sources", "Files to compile. Should just be the 'root' modules, traceur will pull the rest. So for example if A.js requires B.js requires C.js, only list A.js here. Default javascripts/main.js")
    val outputFileName = SettingKey[String]("traceur-output", "Name of the output file. Default main.js")
    val experimental = SettingKey[Boolean]("traceur-experimental", "Turns on all experimental features. Default false")
    val sourceMaps = SettingKey[Boolean]("traceur-source-maps", "Enable source maps generation")
    val includeRuntime = SettingKey[Boolean]("traceur-include-runtime", "If traceur-runtime.js code should be included in the output file. Default true")
    val extraOptions = SettingKey[Seq[String]]("traceur-extra-options", "Extra options to pass to traceur command line")

  }

}

object SbtTraceur extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import

  import com.typesafe.sbt.jse.SbtJsEngine.autoImport.JsEngineKeys._
  import com.typesafe.sbt.jse.SbtJsTask.autoImport.JsTaskKeys._
  import com.typesafe.sbt.traceur.Import.TraceurKeys._
  import com.typesafe.sbt.traceur.Import._
  import com.typesafe.sbt.web.Import.WebKeys._
  import com.typesafe.sbt.web.SbtWeb.autoImport._

  override def projectSettings = Seq(
    includeFilter in traceur := GlobFilter("*.js"),
    sourceFileNames in traceur in Assets := Seq("javascripts/main.js"),
    sourceFileNames in traceur in TestAssets := Seq("javascript-tests/main.js"),
    outputFileName in traceur in Assets := "main.js",
    outputFileName in traceur in TestAssets := "main-test.js",
    experimental := false,
    sourceMaps := true,
    includeRuntime := true,
    extraOptions := Seq(),
    traceur in Assets := runTraceur(Assets).dependsOn(webJarsNodeModules in Plugin).value,
    traceur in TestAssets := runTraceur(TestAssets).dependsOn(webJarsNodeModules in Plugin).value,
    resourceGenerators in Assets <+= traceur in Assets,
    resourceGenerators in TestAssets <+= traceur in TestAssets
  )

  def boolToParam(condition: Boolean, param: String): Seq[String] = {
    if (condition) Seq(param) else Seq()
  }

  private def runTraceur(config: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val sourceDir = (sourceDirectory in config).value
    val outputDir = (resourceManaged in config).value
    val inputFileCandidates = (sourceDir ** (includeFilter in traceur).value).get
    val outputFileNameValue = (outputFileName in traceur in config).value
    val outputFile = outputDir / outputFileNameValue
    val sourceMapFile = outputDir / outputFileNameValue.replace(".js", ".map")
    val fullPathSourceFiles = (sourceFileNames in traceur in config).value
      .map(file => sourceDir / file).filter(_.exists)

    if (fullPathSourceFiles.nonEmpty) {

      val commandlineParameters = (
        boolToParam(experimental.value, "--experimental")
          ++ boolToParam(sourceMaps.value, "--source-maps=file")
          ++ extraOptions.value
          ++ Seq("--out", outputFile.toString)
          ++ fullPathSourceFiles.map(_.toString)
        )

      streams.value.log.info("Compiling with Traceur")

      try {
        SbtJsTask.executeJs(
          state.value,
          // For now traceur only works with node
          EngineType.Node,
          None,
          Nil,
          (webJarsNodeModulesDirectory in Plugin).value / "traceur" / "src" / "node" / "command.js",
          commandlineParameters,
          (timeoutPerSource in traceur).value * inputFileCandidates.size
        )
      } catch {
        case failure: SbtJsTask.JsTaskFailure => {
          val Pattern = "^\\[? *'?(.+?):(\\d+):(\\d+):(.+?)'?,? ?\\]?".r
          val problems = failure.getMessage.split("\n").map {
            case Pattern(path, line, column, message) => {
              new LineBasedProblem(message, Severity.Error, line.toInt, column.toInt - 1, "", new File(path))
            }
            case _ => throw new RuntimeException(failure)
          }

          CompileProblems.report(reporter.value, problems)
        }
      }

      if (includeRuntime.value) {
        val compiled = IO.read(outputFile)
        val runtime = IO.read((webJarsNodeModulesDirectory in Plugin).value / "traceur" / "bin" / "traceur-runtime.js")

        IO.write(outputFile, runtime + compiled)

        if (sourceMaps.value) {
          val runtimeLineCount = runtime.split("\n").length
          val sourceMap = IO.read(sourceMapFile)

          val adjustedSourceMap = sourceMap.replace("\"mappings\":\"", "\"mappings\":\"" + ";" * runtimeLineCount);

          IO.write(sourceMapFile, adjustedSourceMap)
        }
      }

      if (sourceMaps.value) {
        Seq(outputFile, sourceMapFile)
      } else {
        Seq(outputFile)
      }
    } else {
      Seq()
    }
  }
}
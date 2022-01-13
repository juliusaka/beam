package beam.sim

import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.scenario.MutableScenario
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class WritePlansAndStopSimulationTest extends AnyFlatSpec with Matchers with BeamHelper with AppendedClues {

  def readActivitiesTypesFromPlan(pathToPlans: String): Set[String] =
    GenericCsvReader
      .readAsSeq[String](pathToPlans) { row => row.get("activityType") }
      .toSet

  case class LegInfo(personId: String, planIndex: String, legMode: String)

  def readLegsInfoFromPlans(pathToPlans: String): Seq[LegInfo] = {
    GenericCsvReader
      .readAsSeq[(String, LegInfo)](pathToPlans) { row =>
        val person = row.get("personId")
        val planIndex = row.get("planIndex")
        val mode = row.get("legMode") // will be either `null` or string value
        val planElementType = row.get("planElementType") // either "leg" or "activity"
        (planElementType, LegInfo(person, planIndex, mode))
      }
      .filter { case (planElementType, _) => planElementType == "leg" }
      .map { case (_, legInfo) => legInfo }
  }

  def runSimulationAndCatchException(config: Config): String = {
    val (
      beamExecutionConfig: BeamExecutionConfig,
      scenario: MutableScenario,
      beamScenario: BeamScenario,
      services: BeamServices,
      plansMerged: Boolean
    ) = prepareBeamService(config, None)

    val exception: RuntimeException = intercept[RuntimeException] {
      runBeam(
        services,
        scenario,
        beamScenario,
        beamExecutionConfig.outputDirectory,
        plansMerged
      )
    }

    exception.getMessage shouldBe "The simulation was stopped because beam.output.writePlansAndStopSimulation set to true."

    beamExecutionConfig.outputDirectory
  }

  it should "Stop simulation, trow runtime exception and write plans with only Work and Home activities to the output folder." in {
    val config: Config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.simulationName = "beamville_terminated_without_secondary_activities"
           |beam.agentsim.lastIteration = 3
           |beam.output.writePlansAndStopSimulation = true
           |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = false
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = false
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val outputDir = Paths.get(outputDirectory).toFile
    val outputFiles = outputDir.listFiles()
    outputFiles.size shouldNot equal(0)
    outputFiles.map(_.getName) should contain("generatedPlans.csv.gz")
    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    activitiesTypes shouldBe Set("Work", "Home")
  }

  it should "Stop simulation, trow runtime exception and write plans with different activities without modes to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville_terminated_with_secondary_activities_without_modes"
                      |beam.agentsim.lastIteration = 0
                      |beam.output.writePlansAndStopSimulation = true
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = false
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    val personPlanIndexMode = readLegsInfoFromPlans(plansPath.getPath)

    activitiesTypes.contains("Work") shouldBe true
    activitiesTypes.contains("Home") shouldBe true
    activitiesTypes.size should be > 2

    personPlanIndexMode.foreach { legInfo: LegInfo =>
      legInfo.legMode shouldBe null withClue s"in plans for person ${legInfo.personId} with planIndex ${legInfo.planIndex}"
    }
  }

  it should "Stop simulation, trow runtime exception and write plans with only Work and Home activities with modes to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville_terminated_without_secondary_activities_with_modes"
                      |beam.agentsim.lastIteration = 0
                      |beam.output.writePlansAndStopSimulation = true
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = false
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = true
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    val LegInfo = readLegsInfoFromPlans(plansPath.getPath)

    activitiesTypes.contains("Work") shouldBe true
    activitiesTypes.contains("Home") shouldBe true
    activitiesTypes.size shouldBe 2

    LegInfo.length should not be 0
    LegInfo.foreach { legInfo: LegInfo =>
      legInfo.legMode should be(null) withClue s"in plans for person ${legInfo.personId} with planIndex 0"
    }
  }

  it should "Stop simulation, trow runtime exception and write plans with different activities with modes to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville_terminated_with_secondary_activities_with_modes"
                      |beam.agentsim.lastIteration = 0
                      |beam.output.writePlansAndStopSimulation = true
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = true
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile

    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    val personPlanIndexMode = readLegsInfoFromPlans(plansPath.getPath)

    activitiesTypes.contains("Work") shouldBe true
    activitiesTypes.contains("Home") shouldBe true
    activitiesTypes.size should be > 2

    personPlanIndexMode.foreach {
      case LegInfo(person, "0", legMode) =>
        legMode should be(null) withClue s"in plans for person $person with planIndex 0"
      case LegInfo(person, "1", legMode) =>
        legMode shouldNot be(null) withClue s"in plans for person $person with planIndex 1"
      case legInfo => throw new NotImplementedError(s"Unexpected legInfo: $legInfo")
    }
  }
}

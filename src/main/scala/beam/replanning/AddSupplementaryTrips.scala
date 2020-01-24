package beam.replanning

import beam.agentsim.agents.choice.logit.{DestinationMNL, MultinomialLogit}
import beam.sim.population.AttributesOfIndividual
import javax.inject.Inject
import org.matsim.api.core.v01.population.{Activity, HasPlansAndId, Leg, Person, Plan}
import org.matsim.core.config.Config
import org.matsim.core.population.PopulationUtils
import org.matsim.utils.objectattributes.attributable.AttributesUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AddSupplementaryTrips @Inject()(config: Config) extends PlansStrategyAdopter {

  private val log = LoggerFactory.getLogger(classOf[AddSupplementaryTrips])

  override def run(person: HasPlansAndId[Plan, Person]): Unit = {
    log.debug("Before Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
    ReplanningUtil.makeExperiencedMobSimCompatible(person)

    val destinationMNL: MultinomialLogit[DestinationMNL.SupplementaryTripAlternative, DestinationMNL.Parameters] =
      new MultinomialLogit(Map.empty, DestinationMNL.DefaultMNLParameters)

    val supplementaryTripGenerator = new SupplementaryTripGenerator(
      person.getSelectedPlan.getPerson.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
    )

    val newPlan = ReplanningUtil.addNoModeBeamTripsToPlanWithOnlyActivities(
      addSecondaryActivities(
        person.getSelectedPlan,
        person.getSelectedPlan.getPerson,
        supplementaryTripGenerator,
        destinationMNL
      )
    )

    AttributesUtils.copyAttributesFromTo(person.getSelectedPlan, newPlan)

    person.addPlan(newPlan)
    person.setSelectedPlan(newPlan)

    log.debug("After Replanning AddNewActivities: Person-" + person.getId + " - " + person.getPlans.size())
  }

  private def possiblyAddSubtour(
    activity: Activity,
    person: Person,
    generator: SupplementaryTripGenerator,
    destinationMNL: MultinomialLogit[DestinationMNL.SupplementaryTripAlternative, DestinationMNL.Parameters]
  ): List[Activity] = {
    activity.getType match {
      case "Home" => List[Activity](activity)
      case "Work" => generator.generateSubtour(activity, destinationMNL)
      case _      => List[Activity](activity)
    }
  }

  private def addSecondaryActivities(
    plan: Plan,
    person: Person,
    generator: SupplementaryTripGenerator,
    destinationMNL: MultinomialLogit[DestinationMNL.SupplementaryTripAlternative, DestinationMNL.Parameters]
  ): Plan = {
    val newPlan = PopulationUtils.createPlan(plan.getPerson)
    newPlan.setType(plan.getType)

    val elements = plan.getPlanElements.asScala.collect { case activity: Activity => activity }
    val newActivitiesToAdd = elements.zipWithIndex.map {
      case (planElement, idx) =>
        val prevEndTime = if (idx > 0) {
          elements(idx - 1).getEndTime.max(0)
        } else {
          0
        }
        planElement.setMaximumDuration(planElement.getEndTime - prevEndTime)
        possiblyAddSubtour(planElement, person, generator, destinationMNL)
    }
    newActivitiesToAdd.flatten.foreach { x =>
      newPlan.addActivity(x)
    }
    newPlan
  }

}

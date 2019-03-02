package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleState, FuelConsumed}
import beam.agentsim.agents.vehicles.ConsumptionRateFilterStore.{Primary, Secondary}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleCategory.{Bike, Body, Car}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.ChargingType
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, WALK}
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.common.GeoUtils.{Straight, TurningDirection}
import beam.utils.NetworkHelper
import beam.utils.logging.ExponentialLazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.vehicles.Vehicle

/**
  * A [[BeamVehicle]] is a state container __administered__ by a driver ([[PersonAgent]]
  * implementing [[beam.agentsim.agents.modalbehaviors.DrivesVehicle]]). The passengers in the [[BeamVehicle]]
  * are also [[BeamVehicle]]s, however, others are possible). The
  * reference to a parent [[BeamVehicle]] is maintained in its carrier. All other information is
  * managed either through the MATSim [[Vehicle]] interface or within several other classes.
  *
  * @author saf
  * @since Beam 2.0.0
  */
// XXXX: This is a class and MUST NOT be a case class because it contains mutable state.
// If we need immutable state, we will need to operate on this through lenses.

// TODO: safety for
class BeamVehicle(
  val id: Id[BeamVehicle],
  val powerTrain: Powertrain,
  val beamVehicleType: BeamVehicleType
) extends ExponentialLazyLogging {

  var manager: Option[ActorRef] = None

  var spaceTime: SpaceTime = _

  var primaryFuelLevelInJoules = beamVehicleType.primaryFuelCapacityInJoule
  var secondaryFuelLevelInJoules = beamVehicleType.secondaryFuelCapacityInJoule.getOrElse(0.0)

  var mustBeDrivenHome: Boolean = false

  /**
    * The [[PersonAgent]] who is currently driving the vehicle (or None ==> it is idle).
    * Effectively, this is the main controller of the vehicle in space and time in the scenario environment;
    * whereas, the manager is ultimately responsible for assignment and (for now) ownership
    * of the vehicle as a physical property.
    */
  var driver: Option[ActorRef] = None

  var reservedStall: Option[ParkingStall] = None
  var stall: Option[ParkingStall] = None

  /**
    * Called by the driver.
    */
  def unsetDriver(): Unit = {
    driver = None
  }

  /**
    * Only permitted if no driver is currently set. Driver has full autonomy in vehicle, so only
    * a call of [[unsetDriver]] will remove the driver.
    *
    * @param newDriver incoming driver
    */
  def becomeDriver(newDriver: ActorRef): Unit = {
    if (driver.isEmpty) {
      driver = Some(newDriver)
    } else {
      // This is _always_ a programming error.
      // A BeamVehicle is only a data structure, not an Actor.
      // It must be ensured externally, by other means, that only one agent can access
      // it at any time, e.g. by using a ResourceManager etc.
      // Also, this exception is only a "best effort" error detection.
      // Technically, it can also happen that it is _not_ thrown in the failure case,
      // as this method is not synchronized.
      // Don't try to catch this exception.
      throw new RuntimeException("Trying to set a driver where there already is one.")
    }
  }

  def setReservedParkingStall(newStall: Option[ParkingStall]): Unit = {
    reservedStall = newStall
  }

  def useParkingStall(newStall: ParkingStall): Unit = {
    stall = Some(newStall)
  }

  def unsetParkingStall(): Unit = {
    stall = None
  }

  /**
    * useFuel
    *
    * This method estimates energy consumed for [beamLeg] using data in [beamServices]. It accommodates a secondary
    * powertrain and tracks the fuel consumed by each powertrain in cascading order (i.e. primary first until tank is
    * empty and then secondary).
    *
    * IMPORTANT -- This method does nothing to stop a vehicle from moving further than the fuel on-board would allow.
    * When more energy is consumed than the fuel level allows, a warning is logged and the fuel level goes negative.
    * We choose to allow for negative fuel level because this can convey useful information to the user, namely, the
    * amount of increased fuel capacity that would be needed to avoid running out.
    *
    * When fuel level goes negative, it is assumed to happen on the primary power train, not the secondary.
    *
    * It is up to the manager / driver of this vehicle to decide how to react if fuel level becomes negative.
    *
    * @param beamLeg
    * @param beamServices
    * @return FuelConsumed
    */
  def useFuel(beamLeg: BeamLeg, beamServices: BeamServices): FuelConsumed = {
    val fuelConsumptionData =
      BeamVehicle.collectFuelConsumptionData(beamLeg, beamVehicleType, beamServices.networkHelper)
    var primaryEnergyForFullLeg = beamServices.vehicleEnergy.getFuelConsumptionEnergyInJoulesUsing(
      fuelConsumptionData,
      fallBack = powerTrain.getRateInJoulesPerMeter,
      Primary
    )
    var primaryEnergyConsumed = primaryEnergyForFullLeg
    var secondaryEnergyConsumed = 0.0
    if (primaryFuelLevelInJoules < primaryEnergyForFullLeg) {
      if (secondaryFuelLevelInJoules > 0.0) {
        // Use secondary fuel if possible
        val secondaryEnergyForFullLeg = beamServices.vehicleEnergy.getFuelConsumptionEnergyInJoulesUsing(
          fuelConsumptionData,
          fallBack = powerTrain.getRateInJoulesPerMeter,
          Secondary
        )
        secondaryEnergyConsumed = secondaryEnergyForFullLeg * (primaryEnergyForFullLeg - primaryFuelLevelInJoules) / primaryEnergyConsumed
        if (secondaryFuelLevelInJoules < secondaryEnergyConsumed) {
          logger.warn(
            "Vehicle does not have sufficient fuel to make trip (in both primary and secondary fuel tanks), allowing trip to happen and setting fuel level negative: vehicle {} trip distance {} m",
            id,
            beamLeg.travelPath.distanceInM
          )
          primaryEnergyConsumed = primaryEnergyForFullLeg - secondaryFuelLevelInJoules / secondaryEnergyConsumed
          secondaryEnergyConsumed = secondaryFuelLevelInJoules
        } else {
          primaryEnergyConsumed = primaryFuelLevelInJoules
        }
      } else {
        logger.warn(
          "Vehicle does not have sufficient fuel to make trip, allowing trip to happen and setting fuel level negative: vehicle {} trip distance {} m",
          id,
          beamLeg.travelPath.distanceInM
        )
      }
    }
    primaryFuelLevelInJoules = primaryFuelLevelInJoules - primaryEnergyConsumed
    secondaryFuelLevelInJoules = secondaryFuelLevelInJoules - secondaryEnergyConsumed
    FuelConsumed(primaryEnergyConsumed, secondaryEnergyConsumed)
  }

  def addFuel(fuelInJoules: Double): Unit = {
    primaryFuelLevelInJoules = primaryFuelLevelInJoules + fuelInJoules
  }

  /**
    *
    * @return refuelingDuration
    */
  def refuelingSessionDurationAndEnergyInJoules(): (Long, Double) = {
    stall match {
      case Some(theStall) =>
        ChargingType.calculateChargingSessionLengthAndEnergyInJoules(
          theStall.attributes.chargingType,
          primaryFuelLevelInJoules,
          beamVehicleType.primaryFuelCapacityInJoule,
          beamVehicleType.rechargeLevel2RateLimitInWatts,
          beamVehicleType.rechargeLevel3RateLimitInWatts,
          None
        )
      case None =>
        (0, 0.0) // if we are not parked, no refueling can occur
    }
  }

  def getState: BeamVehicleState =
    BeamVehicleState(
      primaryFuelLevelInJoules,
      primaryFuelLevelInJoules / powerTrain.estimateConsumptionInJoules(1),
      driver,
      stall
    )

  def toStreetVehicle: StreetVehicle = {
    val mode = beamVehicleType.vehicleCategory match {
      case Bike =>
        BIKE
      case Car if isCAV =>
        CAV
      case Car =>
        CAR
      case Body =>
        WALK
    }
    StreetVehicle(id, beamVehicleType.id, spaceTime, mode, true)
  }

  def isCAV: Boolean = beamVehicleType.automationLevel > 3

  override def toString = s"$id ($beamVehicleType.id)"
}

object BeamVehicle {

  case class FuelConsumed(primaryFuel: Double, secondaryFuel: Double)

  def noSpecialChars(theString: String): String =
    theString.replaceAll("[\\\\|\\\\^]+", ":")

  def createId[A](id: Id[A], prefix: Option[String] = None): Id[BeamVehicle] = {
    createId(id.toString, prefix)
  }

  def createId[A](id: String, prefix: Option[String]): Id[BeamVehicle] = {
    Id.create(s"${prefix.map(_ + "-").getOrElse("")}${id}", classOf[BeamVehicle])
  }

  case class BeamVehicleState(
    fuelLevel: Double,
    remainingRangeInM: Double,
    driver: Option[ActorRef],
    stall: Option[ParkingStall]
  )

  case class FuelConsumptionData(
    linkId: Int,
    vehicleType: BeamVehicleType,
    linkNumberOfLanes: Option[Int],
    linkCapacity: Option[Double] = None,
    linkLength: Option[Double],
    averageSpeed: Option[Double],
    freeFlowSpeed: Option[Double],
    linkArrivalTime: Option[Long] = None,
    turnAtLinkEnd: Option[TurningDirection] = None,
    numberOfStops: Option[Int] = None
  )

  /**
    * Organizes the fuel consumption data table
    * @param beamLeg Instance of beam leg
    * @param networkHelper the transport network instance
    * @return list of fuel consumption objects generated
    */
  def collectFuelConsumptionData(
    beamLeg: BeamLeg,
    theVehicleType: BeamVehicleType,
    networkHelper: NetworkHelper
  ): IndexedSeq[FuelConsumptionData] = {
    if (beamLeg.mode.isTransit & !Modes.isOnStreetTransit(beamLeg.mode)) {
      Vector.empty
    } else {
      val linkIds = beamLeg.travelPath.linkIds
      val linkTravelTimes: IndexedSeq[Int] = beamLeg.travelPath.linkTravelTime
      // generate the link arrival times for each link ,by adding cumulative travel times of previous links
      val linkArrivalTimes: Seq[Int] = for (i <- linkTravelTimes.indices) yield {
        i match {
          case 0 => beamLeg.startTime
          case _ =>
            beamLeg.startTime + (try {
              linkTravelTimes(i - 1)
            } catch {
              case _: Exception => 0
            })
        }
      }
//      val nextLinkIds = linkIds.takeRight(linkIds.size - 1)
      linkIds.zipWithIndex.map {
        case (id, idx) =>
          val travelTime = linkTravelTimes(idx)
//          val arrivalTime = linkArrivalTimes(idx)
          val currentLink: Option[Link] = networkHelper.getLink(id)
          val averageSpeed = try {
            if (travelTime > 0) currentLink.map(_.getLength).getOrElse(0.0) / travelTime else 0
          } catch {
            case _: Exception => 0.0
          }
          // get the next link , and calculate the direction to be taken based on the angle between the two links
//          val nextLink = if (idx < nextLinkIds.length) {
//            networkHelper.getLink(nextLinkIds(idx))
//          } else {
//            currentLink
//          }
//          val turnAtLinkEnd = currentLink match {
//            case Some(curLink) =>
//              GeoUtils.getDirection(GeoUtils.vectorFromLink(curLink), GeoUtils.vectorFromLink(nextLink.get))
//            case None =>
//              Straight
//          }
//          val numStops = turnAtLinkEnd match {
//            case Straight => 0
//            case _        => 1
//          }
          FuelConsumptionData(
            linkId = id,
            vehicleType = theVehicleType,
            linkNumberOfLanes = currentLink.map(_.getNumberOfLanes().toInt).headOption,
            linkCapacity = None, //currentLink.map(_.getCapacity),
            linkLength = currentLink.map(_.getLength),
            averageSpeed = Some(averageSpeed),
            freeFlowSpeed = currentLink.map(_.getFreespeed),
            linkArrivalTime = None, //Some(arrivalTime),
            turnAtLinkEnd = None, //Some(turnAtLinkEnd),
            numberOfStops = None //Some(numStops)
          )
      }
    }
  }
}

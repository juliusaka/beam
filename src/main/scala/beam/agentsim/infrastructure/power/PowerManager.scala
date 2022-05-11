package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.createId
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import cats.Eval
import com.typesafe.scalalogging.LazyLogging

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerManager(chargingNetworkHelper: ChargingNetworkHelper, beamConfig: BeamConfig) extends LazyLogging {
  import PowerManager._
  private val timeStep = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds
  private val pmcConfigMaybe = beamConfig.beam.agentsim.chargingNetworkManager.powerManagerController

  private[infrastructure] lazy val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(
    chargingNetworkHelper.allChargingStations
  ).value

  private[power] lazy val beamFederateOption: Option[BeamFederate] =
    pmcConfigMaybe match {
      case Some(pmcConfig) if pmcConfig.connect =>
        logger.warn("ChargingNetworkManager should connect to a power grid via Helics...")
        Try {
          logger.info("Init PowerManager Federate...")
          getFederate(
            pmcConfig.federateName,
            pmcConfig.coreType,
            pmcConfig.coreInitString,
            pmcConfig.timeDeltaProperty,
            pmcConfig.intLogLevel,
            pmcConfig.bufferSize,
            pmcConfig.federatePublication match {
              case s: String if s.nonEmpty => Some(s)
              case _                       => None
            },
            (
              pmcConfig.pmcFederateName,
              pmcConfig.pmcFederateSubscription,
              pmcConfig.feedbackEnabled
            ) match {
              case (s1: String, s2: String, feedback: Boolean) if s1.nonEmpty && s2.nonEmpty && feedback =>
                Some(s1 + "/" + s2)
              case _ => None
            }
          )
        }.recoverWith { case e =>
          logger.warn("Cannot init BeamFederate: {}. ChargingNetworkManager is not connected to the grid", e.getMessage)
          Failure(e)
        }.toOption
      case _ => None
    }

  private var physicalBounds = Map.empty[ChargingStation, PhysicalBounds]
  private var currentBin = -1

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    *  @param estimatedLoad map required power per zone
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(
    currentTime: Int,
    estimatedLoad: Option[Map[ChargingStation, PowerInKW]] = None
  ): Map[ChargingStation, PhysicalBounds] = {
    physicalBounds = beamFederateOption match {
      case Some(beamFederate)
          if estimatedLoad.isDefined && (physicalBounds.isEmpty || currentBin < currentTime / timeStep) =>
        logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
        // PUBLISH
        val msgToPublish = estimatedLoad.get.map { case (station, powerInKW) =>
          Map(
            "reservedFor"   -> station.zone.reservedFor,
            "parkingZoneId" -> station.zone.parkingZoneId,
            "estimatedLoad" -> powerInKW
          )
        }
        beamFederate.publishJSON(msgToPublish.toList)

        var gridBounds = List.empty[Map[String, Any]]
        while (gridBounds.isEmpty) {
          // SYNC
          beamFederate.sync(currentTime)
          // COLLECT
          gridBounds = beamFederate.collectJSON()
          // Sleep
          Thread.sleep(1)
        }

        logger.debug("Obtained power from the grid {}...", gridBounds)
        if (beamFederate.dataInStreamPointMaybe.isDefined) {
          gridBounds.flatMap { x =>
            val reservedFor = x("reservedFor").asInstanceOf[String] match {
              case managerIdString if managerIdString.isEmpty => VehicleManager.AnyManager
              case managerIdString =>
                VehicleManager.createOrGetReservedFor(managerIdString, Some(beamConfig)).get
            }
            chargingNetworkHelper
              .get(reservedFor)
              .lookupStation(createId(x("parkingZoneId").asInstanceOf[String])) match {
              case Some(station) =>
                Some(
                  station -> PhysicalBounds(
                    station,
                    x("power_limit_upper").asInstanceOf[PowerInKW],
                    x("power_limit_lower").asInstanceOf[PowerInKW],
                    x("lmp_with_control_signal").asInstanceOf[Double]
                  )
                )
              case _ =>
                logger.error(
                  "Cannot find the charging station correspondent to what has been received from the co-simulation"
                )
                None
            }
          }.toMap
        } else {
          // unlimited physical bounds for now until we figure out why the simulation gets affected
          unlimitedPhysicalBounds
        }
      case _ =>
        logger.debug("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
        unlimitedPhysicalBounds
    }
    currentBin = currentTime / timeStep
    physicalBounds
  }

  /**
    * closing helics connection
    */
  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateOption
      .fold(logger.debug("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
        try {
          logger.debug("Destroying BeamFederate")
          unloadHelics()
        } catch {
          case NonFatal(ex) =>
            logger.error(s"Cannot destroy BeamFederate: ${ex.getMessage}")
        }
      }
  }
}

object PowerManager {
  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Int

  case class PhysicalBounds(
    station: ChargingStation,
    powerLimitUpper: PowerInKW,
    powerLimitLower: PowerInKW,
    lpmWithControlSignal: Double
  )

  /**
    * create unlimited physical bounds
    * @param stations sequence of stations for which to produce physical bounds
    * @return map of physical bounds
    */
  def getUnlimitedPhysicalBounds(stations: Seq[ChargingStation]): Eval[Map[ChargingStation, PhysicalBounds]] = {
    Eval.later {
      stations.map { case station @ ChargingStation(zone) =>
        station -> PhysicalBounds(
          station,
          ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType.get) * zone.maxStalls,
          ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType.get) * zone.maxStalls,
          0.0
        )
      }.toMap
    }
  }
}
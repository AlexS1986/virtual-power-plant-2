package com.example.projections

import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import org.slf4j.LoggerFactory
import com.example.repository.DeviceTemperatureRepository
import com.example._
import repository.ScalikeJdbcSession
import akka.actor.typed.scaladsl.ActorContext

class DeviceTemperatureProjectionHandler(
    tag: String,
    system: ActorSystem[_],
    repo: DeviceTemperatureRepository,
    // context : ActorContext[_]
    )
   
    extends JdbcHandler[
      EventEnvelope[Device.Event],
      ScalikeJdbcSession]() { 

  private val log = LoggerFactory.getLogger(getClass)

  override def process(
      session: ScalikeJdbcSession,
      envelope: EventEnvelope[Device.Event]): Unit = { 
    envelope.event match { 
      /*case Device.EventTemperatureRecorded(persistenceId,temperature) =>
         println("PROJECTIONHANDLER HIT") 
         repo.update(session,persistenceId,temperature)
         logTemperature(session,persistenceId) */
      case Device.EventDataRecorded(persistenceId,capacity,chargeStatus,deliveredEnergy,deliveredEnergyDate) =>
         //println("PROJECTIONHANDLER EVENT DATA HIT")
         //repo.update(session,persistenceId,chargeStatus) // TODO can be removed

         val (groupId,deviceId) = Device.getGroupIdDeviceIdFromPersistenceIdString(persistenceId)
         if(deliveredEnergy != 0.0) repo.recordEnergyDeposit(session,groupId,deviceId,deliveredEnergy,deliveredEnergyDate)
         //logTemperature(session,persistenceId) // TODO can be removed on write side -> unecessary read on DB
      case _ => println("ALERT: PROJECTIONHANDLER HIT2") 
       
    }
  }

  private def logTemperature(
      session: ScalikeJdbcSession,
      deviceId: String): Unit = {
      log.info(
      "DeviceTemperatureProjectionHandler({}) device temperature for '{}': [{}]",
      tag,
      deviceId,
      repo.getTemperature(session, deviceId).getOrElse(0.0)) // get from DB
  }

}
package twin.projections

import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import org.slf4j.LoggerFactory
import twin.repository.DeviceRepository
import twin.Device
import twin.repository.ScalikeJdbcSession
import akka.actor.typed.scaladsl.ActorContext

class DeviceProjectionHandler(
    tag: String,
    system: ActorSystem[_],
    repo: DeviceRepository,
    )
   
    extends JdbcHandler[
      EventEnvelope[Device.Event],
      ScalikeJdbcSession]() { 

  private val log = LoggerFactory.getLogger(getClass)

  override def process(
      session: ScalikeJdbcSession,
      envelope: EventEnvelope[Device.Event]): Unit = { 
    envelope.event match { 
      case Device.EventDataRecorded(persistenceId,capacity,chargeStatus,deliveredEnergy,deliveredEnergyDate) =>
         val (groupId,deviceId) = Device.getGroupIdDeviceIdFromPersistenceIdString(persistenceId)
         if(deliveredEnergy != 0.0) repo.recordEnergyDeposit(session,groupId,deviceId,deliveredEnergy,deliveredEnergyDate)
      case Device.EventChargeStatusPrioritySet(persistenceId,priority) => // this event does not require a projection
      case _ => log.warn("Could not process event of type {}.",envelope.event.toString) 
    }
  }
}
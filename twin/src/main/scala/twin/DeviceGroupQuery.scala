package twin

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import scala.concurrent.duration.FiniteDuration
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.AbstractBehavior

// Cluster Sharding
import akka.cluster.sharding.typed.scaladsl.EntityRef


object DeviceGroupQuery {

  def apply(
      deviceIdToActor: Map[String, EntityRef[Device.Command]],
      requestId: Long,
      requester: ActorRef[DeviceGroup.RespondAllData],
      timeout: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new DeviceGroupQuery(deviceIdToActor, requestId, requester, timeout, context, timers)
      }
    }
  }

  trait Command

  private case object CollectionTimeout extends Command

  final case class WrappedRespondTemperature(response: Device.RespondData) extends Command

  private final case class DeviceTerminated(deviceId: String) extends Command
}

class DeviceGroupQuery(
    deviceIdToActor: Map[String, EntityRef[Device.Command]],
    requestId: Long,
    requester: ActorRef[DeviceGroup.RespondAllData],
    timeout: FiniteDuration,
    context: ActorContext[DeviceGroupQuery.Command],
    timers: TimerScheduler[DeviceGroupQuery.Command])
    extends AbstractBehavior[DeviceGroupQuery.Command](context) {

  import DeviceGroupQuery._
  import DeviceManager.DeviceNotAvailable
  import DeviceManager.DeviceTimedOut
  import DeviceGroup.RespondAllData
  //import DeviceManager.RespondAllTemperatures
  import DeviceManager.Temperature
  import DeviceManager.TemperatureNotAvailable
  import DeviceManager.TemperatureReading

  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondTemperatureAdapter = context.messageAdapter(WrappedRespondTemperature.apply)

  private var repliesSoFar = Map.empty[String, TemperatureReading]
  private var stillWaiting = deviceIdToActor.keySet


  deviceIdToActor.foreach {
    case (deviceId, device) =>
      //context.watchWith(device, DeviceTerminated(deviceId))
      device ! Device.ReadData(0, respondTemperatureAdapter)
  }

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case WrappedRespondTemperature(response) => onRespondTemperature(response)
      case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
      case CollectionTimeout                   => onCollectionTimout()
    }

  private def onRespondTemperature(response: Device.RespondData): Behavior[Command] = {
    val reading = response match {
      case Device.RespondData(_,_,Device.DeviceState(_,Some(value),_),Some(currentHost)) => Temperature(value,currentHost)
      case _        => TemperatureNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  private def onDeviceTerminated(deviceId: String): Behavior[Command] = {
    if (stillWaiting(deviceId)) {
      repliesSoFar += (deviceId -> DeviceNotAvailable)
      stillWaiting -= deviceId
    }
    respondWhenAllCollected()
  }

  private def onCollectionTimout(): Behavior[Command] = {
    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty
    respondWhenAllCollected()
  }

  private def respondWhenAllCollected(): Behavior[Command] = {
    if (stillWaiting.isEmpty) {
      requester ! RespondAllData(requestId, repliesSoFar)
      Behaviors.stopped
    } else {
      this
    }
  }
}


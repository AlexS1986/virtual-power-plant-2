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

import spray.json._
import spray.json.DefaultJsonProtocol._ 
import java.time.LocalDateTime

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName


/**
  * an actor that is spawned to manage a query for the status of all Devices currently belonging to a DeviceGroup
  */
object DeviceGroupQuery {

  def apply(
      deviceIdToActor: Map[String, EntityRef[Device.Command]],
      requester: ActorRef[DeviceGroup.RespondAllData],
      timeout: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new DeviceGroupQuery(deviceIdToActor, requester, timeout, context, timers)
      }
    }
  }

  /**
    * the messages that this actor type can process
    */
  sealed trait Command

  /**
    * this message is sent to this actor when the time limit for waiting for responses from queried Device's has passed
    */
  private final case object CollectionTimeout extends Command with CborSerializable

  /**
    * this message is sent to this actor in order to report data from a particular Device
    *
    * @param response
    */
  private final case class WrappedRespondData(response: Device.RespondData) extends Command with CborSerializable

   

  /**
    * this type defines the status of Devices that are queried by this actor
    * https://doc.akka.io/docs/akka/current/serialization-jackson.html
    */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[DeviceData], name = "deviceData"),
    new JsonSubTypes.Type(value = classOf[DataNotAvailable], name = "dataNotAvailable"),
    new JsonSubTypes.Type(value = classOf[DeviceTimedOut], name = "deviceTimedOut")))
  sealed trait ChargeStatusReading extends CborSerializable

  /**
    * Device data that has been reported successfully 
    *
    * @param value
    * @param currentHost
    */
  final case class DeviceData(value: Double, currentHost: String) extends ChargeStatusReading

  /**
    * Data has not been reported completely yet at the Device
    */
  @JsonDeserialize(`using` = classOf[Formats.DeviceGroupQueryFormats.DataNotAvailableDeserializer])
  sealed trait DataNotAvailable
  @JsonTypeName("dataNotAvailable")
  case object DataNotAvailable extends ChargeStatusReading with DataNotAvailable 

  /**
    * this Device has not responded in time
    */
  @JsonDeserialize(`using` = classOf[Formats.DeviceGroupQueryFormats.DeviceTimedOutDeserializer])
  sealed trait DeviceTimedOut
  @JsonTypeName("deviceTimedOut")
  case object DeviceTimedOut extends ChargeStatusReading with DeviceTimedOut 
}

class DeviceGroupQuery(
    deviceIdToActor: Map[String, EntityRef[Device.Command]],
    requester: ActorRef[DeviceGroup.RespondAllData],
    timeout: FiniteDuration,
    context: ActorContext[DeviceGroupQuery.Command],
    timers: TimerScheduler[DeviceGroupQuery.Command])
    extends AbstractBehavior[DeviceGroupQuery.Command](context) {

  import DeviceGroupQuery._

  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondDataAdapter = context.messageAdapter(WrappedRespondData.apply)

  private var repliesSoFar = Map.empty[String, ChargeStatusReading]
  private var stillWaiting = deviceIdToActor.keySet


  deviceIdToActor.foreach {
    case (deviceId, device) =>
      //context.watchWith(device, DeviceTerminated(deviceId))
      device ! Device.ReadData(respondDataAdapter)
  }

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case WrappedRespondData(response) => onRespondData(response)
      //case DeviceTerminated(deviceId) => onDeviceTerminated(deviceId)
      case CollectionTimeout => onCollectionTimout()
    }

  private def onRespondData(response: Device.RespondData): Behavior[Command] = {
    val reading = response match {
      case Device.RespondData(_,Device.DeviceState(_,List(_,Some(currentChargeStatus)),_,_,_),Some(currentHost)) => DeviceData(currentChargeStatus,currentHost)
      case _ => DataNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  private def onCollectionTimout(): Behavior[Command] = {
    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty
    respondWhenAllCollected()
  }

  private def respondWhenAllCollected(): Behavior[Command] = {
    if (stillWaiting.isEmpty) {
      requester ! DeviceGroup.RespondAllData(repliesSoFar)
      Behaviors.stopped
    } else {
      this
    }
  }
}


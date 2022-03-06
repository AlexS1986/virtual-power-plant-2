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
  private final case object CollectionTimeout extends Command

  /**
    * this message is sent to this actor in order to report data from a particular Device
    *
    * @param response
    */
  private final case class WrappedRespondData(response: Device.RespondData) extends Command

  /**
    * this message is sent to this actor from devices that have been stopped while the query represented by this actor was processed
    *
    * @param deviceId
    */
  //private final case class DeviceTerminated(deviceId: String) extends Command


  /**
    * required to read and write objects of a type with multiple subtypes
    */
  implicit object DataReadingJsonWriter extends RootJsonFormat[DataReading] {
    def write(dataReading: DataReading) : JsValue = {
      dataReading match {
        case DeviceData(value,currentHost) => JsObject("value" -> JsObject("value" -> value.toJson, "currentHost" -> currentHost.toJson),"description" -> "temperature".toJson)
        case DataNotAvailable => JsObject("value" -> "".toJson, "description" -> "temperature not available".toJson)
        //case DeviceNotAvailable => JsObject("value" -> "".toJson, "description" -> "device not available".toJson)
        case DeviceTimedOut => JsObject("value" -> "".toJson, "description" -> "device timed out".toJson)
      }
    }
                                  
    def read(json : JsValue) : DataReading = {
      json.asJsObject.getFields("description") match {
        case Seq(JsString(description)) if description == "temperature" => json.asJsObject.getFields("value") match {
          case Seq(JsNumber(value), JsString(currentHost)) => DeviceData(value.toDouble, currentHost)
          case _ => throw new DeserializationException("Double expected.")
        }
        case Seq(JsString(description)) if description == "temperature not available" => DataNotAvailable
        //case Seq(JsString(description)) if description == "device not available" => DeviceNotAvailable
        case Seq(JsString(description)) if description == "device timed out" => DeviceTimedOut
        case _ => throw new DeserializationException("Temperature Reading expected.")
      }
    } 
  } 

  /**
    * this type defines the status of Devices that are queried by this actor
    */
  sealed trait DataReading

  /**
    * Device data that has been reported successfully 
    *
    * @param value
    * @param currentHost
    */
  final case class DeviceData(value: Double, currentHost: String) extends DataReading

  /**
    * Data has not been reported completely yet at the Device
    */
  case object DataNotAvailable extends DataReading

  /**
    * this Device has been requested to stop before the query represented by this actor could finish
    */
  //case object DeviceNotAvailable extends DataReading

  /**
    * this Device has not responded in time
    */
  case object DeviceTimedOut extends DataReading
}

class DeviceGroupQuery(
    deviceIdToActor: Map[String, EntityRef[Device.Command]],
    requester: ActorRef[DeviceGroup.RespondAllData],
    timeout: FiniteDuration,
    context: ActorContext[DeviceGroupQuery.Command],
    timers: TimerScheduler[DeviceGroupQuery.Command])
    extends AbstractBehavior[DeviceGroupQuery.Command](context) {

  import DeviceGroupQuery._
  import DeviceGroup.RespondAllData

  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondDataAdapter = context.messageAdapter(WrappedRespondData.apply)

  private var repliesSoFar = Map.empty[String, DataReading]
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
      case Device.RespondData(_,Device.DeviceState(_,Some(currentChargeStatus),_,_,_),Some(currentHost)) => DeviceData(currentChargeStatus,currentHost)
      case _ => DataNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  /*private def onDeviceTerminated(deviceId: String): Behavior[Command] = {
    if (stillWaiting(deviceId)) {
      repliesSoFar += (deviceId -> DeviceNotAvailable)
      stillWaiting -= deviceId
    }
    respondWhenAllCollected()
  } */

  private def onCollectionTimout(): Behavior[Command] = {
    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty
    respondWhenAllCollected()
  }

  private def respondWhenAllCollected(): Behavior[Command] = {
    if (stillWaiting.isEmpty) {
      requester ! RespondAllData(repliesSoFar)
      Behaviors.stopped
    } else {
      this
    }
  }
}


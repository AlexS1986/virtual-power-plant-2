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
import spray.json.DefaultJsonProtocol._ // toJson methods etc.
//import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import java.time.LocalDateTime


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



  implicit object TemperatureReadingJsonWriter extends RootJsonFormat[TemperatureReading] {
                    def write(temperatureReading : TemperatureReading) : JsValue = {
                      temperatureReading match {
                        case Temperature(value,currentHost) => JsObject("value" -> JsObject("value" -> value.toJson, "currentHost" -> currentHost.toJson),"description" -> "temperature".toJson)
                        case TemperatureNotAvailable => JsObject("value" -> "".toJson, "description" -> "temperature not available".toJson)
                        case DeviceNotAvailable => JsObject("value" -> "".toJson, "description" -> "device not available".toJson)
                        case DeviceTimedOut => JsObject("value" -> "".toJson, "description" -> "device timed out".toJson)
                      }
                    }
                    def read(json : JsValue) : TemperatureReading = {
                      json.asJsObject.getFields("description") match {
                          case Seq(JsString(description)) if description == "temperature" => json.asJsObject.getFields("value") match {
                              case Seq(JsNumber(value), JsString(currentHost)) => Temperature(value.toDouble, currentHost)
                              case _ => throw new DeserializationException("Double expected.")
                          }
                          case Seq(JsString(description)) if description == "temperature not available" => TemperatureNotAvailable
                          case Seq(JsString(description)) if description == "device not available" => DeviceNotAvailable
                          case Seq(JsString(description)) if description == "device timed out" => DeviceTimedOut
                          case _ => throw new DeserializationException("Temperature Reading expected.")
                      }
                    } // not needed here
                  } 

  sealed trait TemperatureReading
  final case class Temperature(value: Double, currentHost: String) extends TemperatureReading
  case object TemperatureNotAvailable extends TemperatureReading
  case object DeviceNotAvailable extends TemperatureReading
  case object DeviceTimedOut extends TemperatureReading
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
  import DeviceGroup.RespondAllData

  // import DeviceManager.DeviceNotAvailable
  // import DeviceManager.DeviceTimedOut
  
  // //import DeviceManager.RespondAllTemperatures
  // import DeviceManager.Temperature
  // import DeviceManager.TemperatureNotAvailable
  // import DeviceManager.TemperatureReading

  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondTemperatureAdapter = context.messageAdapter(WrappedRespondTemperature.apply)

  private var repliesSoFar = Map.empty[String, TemperatureReading]
  private var stillWaiting = deviceIdToActor.keySet


  deviceIdToActor.foreach {
    case (deviceId, device) =>
      //context.watchWith(device, DeviceTerminated(deviceId))
      device ! Device.ReadData(respondTemperatureAdapter)
  }

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case WrappedRespondTemperature(response) => onRespondTemperature(response)
      case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
      case CollectionTimeout                   => onCollectionTimout()
    }

  private def onRespondTemperature(response: Device.RespondData): Behavior[Command] = {
    val reading = response match {
      case Device.RespondData(_,Device.DeviceState(_,Some(value),_),Some(currentHost)) => Temperature(value,currentHost)
      case _ => TemperatureNotAvailable
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


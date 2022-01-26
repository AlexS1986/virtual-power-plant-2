package com.example

import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import spray.json._
import spray.json.DefaultJsonProtocol._ // toJson methods etc.
//import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

// DeviceManager

object DeviceManager {
  def apply(): Behavior[Command] =
    Behaviors.setup(context => new DeviceManager(context))

  trait Command

  final case class RequestTrackDevice(
      groupId: String,
      deviceId: String,
      replyTo: ActorRef[DeviceRegistered]
  ) extends DeviceManager.Command
      with DeviceGroup.Command

  final case class DeviceRegistered(deviceId: String) extends NetworkActor.Command // TODO do not extend Clients Commands

  final case class RequestDeviceList(
      requestId: Long,
      groupId: String,
      replyTo: ActorRef[ReplyDeviceList]
  ) extends DeviceManager.Command
      with DeviceGroup.Command

  final case class ReplyDeviceList(requestId: Long, ids: Set[String])

  private final case class DeviceGroupTerminated(groupId: String)
      extends DeviceManager.Command

  final case class RequestAllTemperatures(
      requestId: Long,
      groupId: String,
      replyTo: ActorRef[RespondAllTemperatures]
  ) extends DeviceGroupQuery.Command
      with DeviceGroup.Command
      with DeviceManager.Command

  final case class RespondAllTemperatures(
      requestId: Long,
      temperatures: Map[String, TemperatureReading]
  )


  implicit object TemperatureReadingJsonWriter extends RootJsonFormat[DeviceManager.TemperatureReading] {
                    def write(temperatureReading : DeviceManager.TemperatureReading) : JsValue = {
                      temperatureReading match {
                        case DeviceManager.Temperature(value,currentHost) => JsObject("value" -> JsObject("value" -> value.toJson, "currentHost" -> currentHost.toJson),"description" -> "temperature".toJson)
                        case DeviceManager.TemperatureNotAvailable => JsObject("value" -> "".toJson, "description" -> "temperature not available".toJson)
                        case DeviceManager.DeviceNotAvailable => JsObject("value" -> "".toJson, "description" -> "device not available".toJson)
                        case DeviceManager.DeviceTimedOut => JsObject("value" -> "".toJson, "description" -> "device timed out".toJson)
                      }
                    }
                    def read(json : JsValue) : DeviceManager.TemperatureReading = {
                      json.asJsObject.getFields("description") match {
                          case Seq(JsString(description)) if description == "temperature" => json.asJsObject.getFields("value") match {
                              case Seq(JsNumber(value), JsString(currentHost)) => DeviceManager.Temperature(value.toDouble, currentHost)
                              case _ => throw new DeserializationException("Double expected.")
                          }
                          case Seq(JsString(description)) if description == "temperature not available" => DeviceManager.TemperatureNotAvailable
                          case Seq(JsString(description)) if description == "device not available" => DeviceManager.DeviceNotAvailable
                          case Seq(JsString(description)) if description == "device timed out" => DeviceManager.DeviceTimedOut
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

class DeviceManager(context: ActorContext[DeviceManager.Command])
    extends AbstractBehavior[DeviceManager.Command](context) {
  import DeviceManager._

  var groupIdToActor = Map.empty[String, EntityRef[DeviceGroup.Command]]

  val sharding = ClusterSharding(context.system)

  context.log.info("DeviceManager started")

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, _, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! trackMsg
          case None =>
            context.log.info("Creating device group actor for {}", groupId)
            val groupActor = sharding.entityRefFor(
                  DeviceGroup.TypeKey,groupId)

            //val groupActor =
            //  context.spawn(DeviceGroup(groupId), "group-" + groupId)
            //context.watchWith(groupActor, DeviceGroupTerminated(groupId))
            groupActor ! trackMsg
            groupIdToActor += groupId -> groupActor
        }
        this

      case req @ RequestDeviceList(requestId, groupId, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! req
          case None =>
            replyTo ! ReplyDeviceList(requestId, Set.empty)
        }
        this

      case DeviceGroupTerminated(groupId) =>
        context.log.info(
          "Device group actor for {} has been terminated",
          groupId
        )
        groupIdToActor -= groupId
        this
    }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info("DeviceManager stopped")
      this
  }

}

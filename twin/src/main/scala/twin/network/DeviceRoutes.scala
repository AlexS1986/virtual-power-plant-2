package twin.network 

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import akka.actor.typed.ActorRef

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import scala.concurrent.Future
import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ContentTypes
import scala.util.Success
import scala.util.Failure

//import scala.util.parsing.json._
import spray.json._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers._

import akka.http.scaladsl.server.Directives._
import twin._

private[twin] final class DeviceRoutes(
    system: ActorSystem[_],
    //networkActor: ActorRef[NetworkActor.Command],
    implicit val deviceManagers: Seq[ActorRef[DeviceManager.Command]],
    deviceManager: ActorRef[DeviceManager.Command] // TODO array of deviceManagers that serve requests in round robin manner?
) {

  
  final case class StopDevice(deviceId: String, groupId: String)
  implicit val stopDeviceFormat  = jsonFormat2(StopDevice)

  final case class DeviceIdentifier(deviceId: String, groupId: String)
  implicit val deviceIdentifierFormat = jsonFormat2(DeviceIdentifier)

  final case class RecordData(groupId: String, deviceId: String, capacity: Double, chargeStatus: Double, deliveredEnergy: Double)
  implicit val recordDataFormat = jsonFormat5(RecordData)

  final case class GroupIdentifier(groupId: String)
  implicit val groupIdentifierFormat = jsonFormat1(GroupIdentifier)

  // to render JSON for response
  case class DeviceData(data:Double, currentHost:String)
  implicit val deviceDataFormat = jsonFormat2(DeviceData)

  implicit val executionContext = system.executionContext

  private def getDeviceManager(implicit deviceManagers : Seq[ActorRef[DeviceManager.Command]] ) : Option[ActorRef[DeviceManager.Command]] = {
    if (deviceManagers.isEmpty) {
      None
    } else {
      println(deviceManagers((math.random() * deviceManagers.size).toInt))
      Some(deviceManagers((math.random() * deviceManagers.size).toInt))
    }
  }
 
  val devices: Route =
    concat(
      path("twin" / "stop") {  // sends a command to an existing device to stop it
        entity(as[DeviceIdentifier]) { deviceIdentifier => //
          getDeviceManager match {
            case Some(deviceManager) => deviceManager ! DeviceManager.StopDevice(deviceIdentifier.deviceId,deviceIdentifier.groupId) 
            case None => complete(StatusCodes.InternalServerError)
          }
          complete(StatusCodes.Accepted,HttpEntity(ContentTypes.`text/html(UTF-8)`, "Stop request received at twin microservice."))
        }
      },
      path("twin" / "track-device") {
        post {
          entity(as[DeviceIdentifier]) { 
            deviceIdentifier =>
              import akka.util.Timeout
              import akka.actor.typed.scaladsl.AskPattern._
              import scala.concurrent.duration._
              import scala.concurrent.{ExecutionContext, Future}
              implicit val timeout: Timeout = 5.seconds
              implicit val actorSystem      = system

              getDeviceManager match {
                case Some(deviceManager) => deviceManager.ask(replyTo =>  DeviceManager.RequestTrackDevice(deviceIdentifier.groupId,deviceIdentifier.deviceId, replyTo)) // TODO: handling of response required?
                                            complete(StatusCodes.Accepted, "Device track request received")
                case None => complete(StatusCodes.InternalServerError)
              }
          }
        }
      },
      path("twin" / "untrack-device") {
        post { // TODO stop tracking a device as a twin, is sent by simulator (may be send synchronously?)
          entity(as[DeviceIdentifier]) { 
            deviceIdentifier =>
              getDeviceManager match {
                case Some(deviceManager) => deviceManager ! DeviceManager.RequestUnTrackDevice(deviceIdentifier.groupId,deviceIdentifier.deviceId)
                                            complete(StatusCodes.Accepted, "Device untrack request received")
                case None => complete(StatusCodes.InternalServerError)
              } 
          }
        }
      },
      path("twin" / "data") {
        concat(
          get {
            entity((as[DeviceIdentifier])) { deviceIdentifier =>
              onComplete{
                import akka.util.Timeout
                import akka.actor.typed.scaladsl.AskPattern._
                import scala.concurrent.duration._
                import scala.concurrent.{ExecutionContext, Future}
                implicit val timeout: Timeout = 5.seconds
                implicit val actorSystem      = system

                val result = getDeviceManager match {
                  case Some(deviceManager) => 
                    deviceManager//networkActor
                      .ask((replyTo: ActorRef[Device.RespondData]) =>
                          DeviceManager.RequestData(deviceIdentifier.groupId, deviceIdentifier.deviceId, replyTo))
                    .map {
                      deviceResponse =>
                        deviceResponse match {
                          case Device.RespondData(_,Device.DeviceState(_,Some(lastChargeStatusReading),_),Some(currentHost)) => 
                              val dataJson = DeviceData(lastChargeStatusReading,currentHost).toJson
                              dataJson.toString
                          case Device.RespondData(_, Device.DeviceState(_,None,_),_) => "{}"
                          case _ => "{}" 
                    }
                  }
                  case None => throw new Exception("Internal server error")
                }
                result
              } {
                case Success(result) => 
                    complete(HttpEntity(ContentTypes.`application/json`, result))
                case Failure(exception)  => 
                    complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
              }
            }
          },
          post { // TODO does not need to use ask pattern?
            entity((as[RecordData])) { recordDataRequest =>
              getDeviceManager match {
                case Some(deviceManager) => deviceManager ! DeviceManager
                    .RecordData(
                      recordDataRequest.groupId,
                      recordDataRequest.deviceId,
                      recordDataRequest.capacity,
                      recordDataRequest.chargeStatus,
                      recordDataRequest.deliveredEnergy,
                    )
                    complete(StatusCodes.Accepted)
                case None => complete(StatusCodes.InternalServerError)
               }
            }
          }
        )
      },
      path("twin" / "data-all") {
        get {
          entity(as[GroupIdentifier]) { groupIdentifier => 
            onComplete {
              import akka.util.Timeout
              import akka.actor.typed.scaladsl.AskPattern._
              import scala.concurrent.duration._
              import scala.concurrent.{ExecutionContext, Future}
              implicit val timeout: Timeout = 5.seconds
              implicit val actorSystem      = system

              val result = getDeviceManager match {
                case Some(deviceManager) => deviceManager.ask((replyTo: ActorRef[DeviceGroup.RespondAllData]) =>
                  DeviceManager.RequestAllData(groupIdentifier.groupId,replyTo)) // TODO request ID? not necessary if ask syncronously which has to be done here to respond
                  .map {
                    import DeviceGroupQuery.TemperatureReadingJsonWriter
                    respondData => respondData.data.toJson.toString   
                  }
                case None => throw new Exception("Internal server error")
              }
              result
            } {
                case Success(respondData) => complete(HttpEntity(ContentTypes.`application/json`, respondData))
                case Failure(exception)  => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
            }
                 
          }
        }
      },
    )
}
package twin.network 

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers._

import spray.json._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import scala.util.parsing.json._

import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure

import twin._

import java.time.LocalDateTime
import twin.Device.Priority.High
import twin.Device.Priority.Low


private[twin] final class TwinRoutes(
    system: ActorSystem[_],
    implicit val deviceManagers: Seq[ActorRef[DeviceManager.Command]],
) {
  import Formats.localDateTimeFormat
  import Formats.priorityFormat

  //val readsideHost = system.settings.config.getConfig("readside").getString("host")
  //val readsidePort = system.settings.config.getConfig("readside").getString("port")
  //val routeToReadside = "http://" + readsideHost + ":" + readsidePort  + "/twin-readside"

  /**
    * represents the body of a http-request to request a certain total energy output
    *
    * @param groupId the id of the DeviceGroup 
    * @param desiredEnergyOutput the desired total energy output for the next 10s
    * @param relaxationParameter parameter that can be used to control oscillations of the total energy output
    */
  final case class TotalDesiredEnergyOutputMessage(groupId: String, desiredEnergyOutput: Double, relaxationParameter: Double)
  implicit val TotalDesiredEnergyOutputMessageF = jsonFormat3(TotalDesiredEnergyOutputMessage)

  /**
      * this message is sent to this microservice in order tell a particular Device to set its desired charge status
      *
      * @param deviceId
      * @param groupId
      * @param desiredChargeStatus
      */
  final case class DesiredChargeStatusMessage(deviceId: String, groupId: String, desiredChargeStatus: Double) 
  implicit val desiredChargeStatusMessageFormat = jsonFormat3(DesiredChargeStatusMessage)

  /**
    * represents the body of a http-request that identifies a Device
    *
    * @param deviceId
    * @param groupId
    */
  final case class DeviceIdentifier(deviceId: String, groupId: String)
  implicit val deviceIdentifierFormat = jsonFormat2(DeviceIdentifier)


  /**
    * represents the body of a http-request to record data for a Device
    *
    * @param groupId the DeviceGroup 
    * @param deviceId the ID of the Device
    * @param capacity the current capacity of the Device
    * @param chargeStatus the current charge status of the Device
    * @param deliveredEnergy the energy emitted to the grid
    * @param deliveredEnergyDate the date time at which the energy was emitted to the grid
    */
  final case class RecordData(groupId: String, deviceId: String, capacity: Double, chargeStatus: Double, deliveredEnergy: Double, deliveredEnergyDate: LocalDateTime)
  implicit val recordDataFormat = jsonFormat6(RecordData)

  /**
    * represents the body of a http-request that identifies a DeviceGroup
    *
    * @param groupId
    */
  final case class GroupIdentifier(groupId: String)
  implicit val groupIdentifierFormat = jsonFormat1(GroupIdentifier)

  /**
    * represents the body of a http-request that represents the current state of the device
    *
    * @param chargeStatus the current chargeStatus
    * @param lastTenDeliveredEnergyReadings the last ten energy deposits to the grid
    * @param currentHost the id of the current instance of the twin microservice hosting the Device
    * @param priority the current priority level of messages that the Device accepts
    */
  case class DeviceData(chargeStatus: Double, lastTenDeliveredEnergyReadings: List[Option[Double]] , currentHost: String, priority: Device.Priority)
  implicit val deviceDataFormat = jsonFormat4(DeviceData)
  

  implicit val executionContext = system.executionContext

  /**
    * returns an ActorReference to one of multiple DeviceManager actors
    *
    * @param deviceManagers
    * @return
    */
  private def getDeviceManager(implicit deviceManagers : Seq[ActorRef[DeviceManager.Command]] ) : Option[ActorRef[DeviceManager.Command]] = {
    if (deviceManagers.isEmpty) {
      None
    } else {
      Some(deviceManagers((math.random() * deviceManagers.size).toInt))
    }
  }

  // settings for ask pattern in synchronous call
  import akka.util.Timeout
  import akka.actor.typed.scaladsl.AskPattern._
  import scala.concurrent.duration._
  import scala.concurrent.{ExecutionContext, Future}
  implicit val timeout: Timeout = 5.seconds
  implicit val actorSystem      = system
 
  val devices: Route =
    concat(
      path("twin" / "stop") {  // sends a command to an existing device to stop it
        entity(as[DeviceIdentifier]) { deviceIdentifier => //
          val deviceManagerOption : Option[ActorRef[DeviceManager.Command]] = getDeviceManager
          deviceManagerOption match {
            case Some(deviceManager) => deviceManager ! DeviceManager.StopDevice(deviceIdentifier.deviceId,deviceIdentifier.groupId) 
            case None => complete(StatusCodes.InternalServerError)
          }
          complete(StatusCodes.Accepted,HttpEntity(ContentTypes.`text/html(UTF-8)`, "Stop request received at twin microservice."))
        }
      },
      path("twin" / "track-device") { // only after "track-device" has been called, other messages to the Device are accepted
        post {
          entity(as[DeviceIdentifier]) { 
            deviceIdentifier =>
              val deviceManagerOption : Option[ActorRef[DeviceManager.Command]] = getDeviceManager
              deviceManagerOption match {
                case Some(deviceManager) => deviceManager.ask(replyTo =>  DeviceManager.RequestTrackDevice(deviceIdentifier.groupId,deviceIdentifier.deviceId, replyTo)) // TODO: handling of response required?
                                            complete(StatusCodes.Accepted, "Device track request received")
                case None => complete(StatusCodes.InternalServerError)
              }
          }
        }
      },
      path("twin" / "untrack-device") {
        post { 
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
      path("twin" / "charge-status" / "priority" / "reset") {
        post {
          entity(as[DeviceIdentifier]) { 
              deviceIdentifier =>
                getDeviceManager match {
                  case Some(deviceManager) => deviceManager ! DeviceManager.ResetPriority(deviceIdentifier.deviceId, deviceIdentifier.groupId)
                                              complete(StatusCodes.Accepted, s"Request to reset the charge status command priority of device ${deviceIdentifier.deviceId} in VPP ${deviceIdentifier.groupId} received.")
                  case None => complete(StatusCodes.InternalServerError)
                } 
            }
        } 
      },
      path("twin" / "charge-status") {
          post { 
            entity(as[DesiredChargeStatusMessage]) { 
              desiredChargeStatusMessage =>
                getDeviceManager match {
                  case Some(deviceManager) => deviceManager ! DeviceManager.SetDesiredChargeStatus(desiredChargeStatusMessage.deviceId,desiredChargeStatusMessage.groupId,desiredChargeStatusMessage.desiredChargeStatus)
                                              complete(StatusCodes.Accepted, s"DesiredChargeStatusMessage(${desiredChargeStatusMessage.deviceId},${desiredChargeStatusMessage.groupId},${desiredChargeStatusMessage.desiredChargeStatus}) received")
                  case None => complete(StatusCodes.InternalServerError)
                } 
            }
          }
      },
      path("twin" / "desired-total-energy-output") {
        post {
          entity(as[TotalDesiredEnergyOutputMessage]) { totalDesiredEnergyOutputMessage => 
            getDeviceManager match {
                          case Some(deviceManager) => deviceManager ! DeviceManager.DesiredTotalEnergyOutput(totalDesiredEnergyOutputMessage.groupId,totalDesiredEnergyOutputMessage.desiredEnergyOutput, totalDesiredEnergyOutputMessage.relaxationParameter)
                                                      complete(StatusCodes.OK, s"Desired total energy output message received for VPP ${totalDesiredEnergyOutputMessage.groupId} for value ${totalDesiredEnergyOutputMessage.desiredEnergyOutput}")
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
                val result = getDeviceManager match {
                  case Some(deviceManager) => 
                    deviceManager
                      .ask((replyTo: ActorRef[Device.RespondData]) =>
                          DeviceManager.RequestData(deviceIdentifier.groupId, deviceIdentifier.deviceId, replyTo))
                    .map {
                      deviceResponse =>
                        deviceResponse match {
                          case Device.RespondData(_,Device.DeviceState(_,List(_,Some(lastChargeStatusReading)),_,lastTenDeliveredEnergyReadings,priority),Some(currentHost)) => 
                              val dataJson = DeviceData(lastChargeStatusReading, lastTenDeliveredEnergyReadings,currentHost, priority).toJson
                              dataJson.toString
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
          post {
            entity((as[RecordData])) { recordDataRequest =>
              getDeviceManager match {
                case Some(deviceManager) => deviceManager ! DeviceManager
                    .RecordData(
                      recordDataRequest.groupId,
                      recordDataRequest.deviceId,
                      recordDataRequest.capacity,
                      recordDataRequest.chargeStatus,
                      recordDataRequest.deliveredEnergy,
                      recordDataRequest.deliveredEnergyDate
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
              val result = getDeviceManager match {
                case Some(deviceManager) => deviceManager.ask((replyTo: ActorRef[DeviceGroup.RespondAllData]) =>
                  DeviceManager.RequestAllData(groupIdentifier.groupId,replyTo))  
                  .map {
                    import DeviceGroupQuery.ChargeStatusReadingJsonWriter
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



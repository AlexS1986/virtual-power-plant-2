package com.example

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

private[example] final class DeviceRoutes(
    system: ActorSystem[_],
    networkActor: ActorRef[NetworkActor.Command]
) {

  //final case class RequestCheckIfDeviceTracked(deviceId: String)
  final case class RequestHostName(groupId: String, deviceId: String) // TODO extends HttpMessage as in simulator
  final case class RequestTemperature(groupId: String, deviceId: String)
  final case class RecordTemperature(groupId: String, deviceId: String, value: Double)
  
  final case class StopDevice(deviceId: String, groupId: String)
  implicit val stopDeviceFormat  = jsonFormat2(StopDevice)

  final case class RecordData(
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double
  )

  case class TemperaturesRequest(groupId:String)
  implicit val temperaturesRequestFormat = jsonFormat1(TemperaturesRequest)

  case class DeviceData(data:Double, currentHost:String)
  implicit val deviceDataFormat = jsonFormat2(DeviceData)

  implicit val executionContext = system.executionContext

  implicit val trackDeviceFormat    = jsonFormat2(NetworkActor.RequestTrackDevice) // why here?
  implicit val unTrackDeviceFormat    = jsonFormat2(NetworkActor.RequestUnTrackDevice)

  //implicit val checkIfDeviceTracked = jsonFormat1(RequestCheckIfDeviceTracked)
  implicit val requestHostname      = jsonFormat2(RequestHostName)
  implicit val requestTemperature   = jsonFormat2(RequestTemperature)
  implicit val recordTemperature = jsonFormat3(
    RecordTemperature
  ) //jsonFormat4(NetworkActor.RecordTemperature)
  implicit val recordData = jsonFormat5(RecordData)

  val devices: Route =
    concat(
      path("stop") {
        entity(as[StopDevice]) { stopSimulation => //
         println("STOP requested: " + stopSimulation.deviceId + " " +stopSimulation.groupId )
          networkActor ! NetworkActor.StopDevice(stopSimulation.deviceId,stopSimulation.groupId)
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Stop request received at twin."))
        }
      },
      post {
        path("track-device") {
          entity(as[NetworkActor.RequestTrackDevice]) { // also as route define
            msg =>
              /*import akka.util.Timeout
              import akka.actor.typed.scaladsl.AskPattern._
              import scala.concurrent.duration._
              import scala.concurrent.{ExecutionContext, Future}
              implicit val timeout: Timeout = 5.seconds

              implicit val actorSystem = system */

              networkActor ! msg

              /*val result  = networkActor.ask((replyTo : ActorRef[NetworkActor.DeviceTracked]) => NetworkActor.CheckIfDeviceTracked(msg.deviceId,replyTo)).map{
                        res => res match {
                          case NetworkActor.DeviceTracked(true) => s"Device ${msg.deviceId} registered."
                          case _ => "Could not register device."
                        }
                      }

                      complete(StatusCodes.Accepted,result) */

              complete(StatusCodes.Accepted, "Device track request received")
          }
        }
      },
      post {
        path("untrack-device") {
          entity(as[NetworkActor.RequestUnTrackDevice]) { // also as route define
            msg =>
              /*import akka.util.Timeout
              import akka.actor.typed.scaladsl.AskPattern._
              import scala.concurrent.duration._
              import scala.concurrent.{ExecutionContext, Future}
              implicit val timeout: Timeout = 5.seconds

              implicit val actorSystem = system */

              networkActor ! msg

              /*val result  = networkActor.ask((replyTo : ActorRef[NetworkActor.DeviceTracked]) => NetworkActor.CheckIfDeviceTracked(msg.deviceId,replyTo)).map{
                        res => res match {
                          case NetworkActor.DeviceTracked(true) => s"Device ${msg.deviceId} registered."
                          case _ => "Could not register device."
                        }
                      }

                      complete(StatusCodes.Accepted,result) */

              complete(StatusCodes.Accepted, "Device untrack request received")
          }
        }
      },
     /* get {
        path("check-if-tracked") {
          entity((as[RequestCheckIfDeviceTracked])) { request =>
            import akka.util.Timeout
            import akka.actor.typed.scaladsl.AskPattern._
            import scala.concurrent.duration._
            import scala.concurrent.{ExecutionContext, Future}
            implicit val timeout: Timeout = 5.seconds
            implicit val actorSystem      = system

            val result = networkActor
              .ask((replyTo: ActorRef[NetworkActor.DeviceTracked]) =>
                NetworkActor.CheckIfDeviceTracked(request.deviceId, replyTo)
              )
              .map { res =>
                res match {
                  case NetworkActor.DeviceTracked(true) =>
                    s"Device ${request.deviceId} already tracked."
                  case _ => s"Device ${request.deviceId} not yet tracked."
                }
              }
            complete(StatusCodes.Accepted, result)
          }
        }
      }, */
      get {
        path("temperature") {
          entity((as[RequestHostName])) { hostnameRequest =>
            onComplete{
              import akka.util.Timeout
              import akka.actor.typed.scaladsl.AskPattern._
              import scala.concurrent.duration._
              import scala.concurrent.{ExecutionContext, Future}
              implicit val timeout: Timeout = 5.seconds
              implicit val actorSystem      = system

              val result = networkActor
                .ask((replyTo: ActorRef[Device.RespondTemperature]) =>
                  NetworkActor
                    .RequestTemperature(hostnameRequest.groupId, hostnameRequest.deviceId, replyTo)
                )
                .map { res =>
                  res match {
                    case Device.RespondTemperature(
                          _,
                          _,
                          Some(temperature),
                          Some(currentHost)
                        ) => 
                          val stateJson = DeviceData(temperature,currentHost).toJson
                          stateJson.toString
                    case Device.RespondTemperature(_, _, None,_) => "{}"
                    case _ => "{}" //Map("error" -> "could not read data").toJson
                    //s"No temperature recorded"
                  }
                }
                result
            } {
              case Success(result) => 
                   complete(HttpEntity(ContentTypes.`application/json`, result))
              case Failure(exception)  => 
                   complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
            }
          }
        }
      },
      path("temperatures") {
        get {
          entity(as[TemperaturesRequest]) { temperaturesRequest => 
            //println("ALERT!!!!! temperatures request for "+temperaturesRequest.groupId)
            onComplete {
              import akka.util.Timeout
              import akka.actor.typed.scaladsl.AskPattern._
              import scala.concurrent.duration._
              import scala.concurrent.{ExecutionContext, Future}
              implicit val timeout: Timeout = 5.seconds
              implicit val actorSystem      = system

              val result = networkActor.ask((replyTo: ActorRef[DeviceManager.RespondAllTemperatures]) =>
                  NetworkActor
                    .RequestAllTemperatures(
                      temperaturesRequest.groupId,
                      replyTo
                    )
                ).map{
                  import DeviceManager.TemperatureReadingJsonWriter
                  respondAllTemperatures => respondAllTemperatures.temperatures.toJson.toString//println("RESPONSE",respondAllTemperatures.temperatures.toJson.toString)
                    
                }
              result  
            } {
                case Success(respondAllTemperaturesMessage) => complete(HttpEntity(ContentTypes.`application/json`, respondAllTemperaturesMessage))
                case Failure(exception)  => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
            }
                 
          }
        }
      },
      post {
        path("data") {
          entity((as[RecordData])) { recordDataRequest =>
            import akka.util.Timeout
            import akka.actor.typed.scaladsl.AskPattern._
            import scala.concurrent.duration._
            import scala.concurrent.{ExecutionContext, Future}
            implicit val timeout: Timeout = 5.seconds
            implicit val actorSystem      = system

            val result = networkActor
              .ask((replyTo: ActorRef[Device.DataRecorded]) =>
                NetworkActor
                  .RecordData(
                    recordDataRequest.groupId,
                    recordDataRequest.deviceId,
                    recordDataRequest.capacity,
                    recordDataRequest.chargeStatus,
                    recordDataRequest.deliveredEnergy,
                    replyTo
                  )
              )
              .map { res =>
                res match {
                  case Device.DataRecorded(requestId) =>
                    s"Temperature recorded"
                  case _ => s"Could not record temperature"
                }
              }
            complete(StatusCodes.Accepted, result)

          }
        }
      },
      get {
        path("hostname") {
          entity((as[RequestTemperature])) { temperatureRequest =>
            import akka.util.Timeout
            import akka.actor.typed.scaladsl.AskPattern._
            import scala.concurrent.duration._
            import scala.concurrent.{ExecutionContext, Future}
            implicit val timeout: Timeout = 5.seconds
            implicit val actorSystem      = system

            val result = networkActor
              .ask((replyTo: ActorRef[NetworkActor.HostName]) =>
                NetworkActor
                  .RequestHostname(temperatureRequest.groupId, temperatureRequest.deviceId, replyTo)
              )
              .map { res =>
                res match {
                  case NetworkActor.HostName(hostname) if hostname.size > 0 =>
                    s"Host name is $hostname"
                  case _ => s"Hostname not found"
                }
              }
            complete(StatusCodes.Accepted, result)

          }
        }
      }
    )

  /*val devices: Route =
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
      }
    } */

}

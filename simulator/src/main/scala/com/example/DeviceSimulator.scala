package com.example

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import spray.json.DefaultJsonProtocol._

import spray.json._
import scala.concurrent.Future
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.Http
import akka.actor.typed.ActorSystem
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpMethod
import scala.util.Failure
import scala.util.Success

object DeviceSimulator {
  sealed trait Command
  case object StartSimulation                        extends Command
  case object StopSimulation                         extends Command
  case object UntrackDeviceAtTwin                    extends Command
  final case class RunSimulation(parameters: String) extends Command

  //sealed trait TwinCommand
  //case object StopSimulationTwin extends TwinCommand with Command

  final case class RecordData(
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double
  )
  implicit val recordDataFormat = jsonFormat5(RecordData)

  final case class GroupIdDeviceId(groupId:String, deviceId : String)
  implicit val groupIdDeviceIdFormat = jsonFormat2(GroupIdDeviceId)


  def apply(
      deviceId: String,
      groupId: String,
      capacity: Double,
      urlToPostData: String,
      urlToRequestTracking: String,
      urlToRequestStopTracking: String,
  ): Behavior[Command] = {
    getNewBehavior(groupId, deviceId, capacity, 0, urlToPostData, urlToRequestTracking,urlToRequestStopTracking, false)
  }

  def getNewBehavior(
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      routeToPostTemperature: String,
      urlToRequestTracking: String,
      urlToRequestUnTracking: String,
      simulationRunning: Boolean
  ): Behavior[Command] =
    Behaviors.receive[Command] { (context, message) =>
      implicit val system: ActorSystem[_] = context.system

      message match {
        case StartSimulation => // TODO needs to send track device to Twin
          sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestTracking,HttpMethods.POST)
          if (!simulationRunning) {
            context.self ! RunSimulation("even")
            getNewBehavior(
              groupId,
              deviceId,
              capacity,
              chargeStatus,
              routeToPostTemperature,
              urlToRequestTracking,
              urlToRequestUnTracking,
              true
            )
          } else {
            Behaviors.same
          }
        case StopSimulation => // TODO send stop to twin so that device can be removed
          //sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestUnTracking, HttpMethods.POST)
          
          context.scheduleOnce(10.seconds, context.self,UntrackDeviceAtTwin) // give all messages time to be processed
          Behaviors.same
        case UntrackDeviceAtTwin => 
          Behaviors.stopped{ () => sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestUnTracking, HttpMethods.POST,true)}
          
        case RunSimulation(parameters) => {
          
          val (message, delay) =
            simulateDevice(
              groupId,
              deviceId,
              routeToPostTemperature,
              parameters
            )
          context.scheduleOnce(delay, context.self, message)
          Behaviors.same
        }
      }
    }

  def simulateDevice(
      groupId: String,
      deviceId: String,
      urlToPostData: String,
      parameters: String
  )(implicit system: ActorSystem[_]): (RunSimulation, FiniteDuration) = {
    val recordTemperature =
      if (parameters == "even")
        RecordData(groupId, deviceId, 100.0,chargeStatus = 20, deliveredEnergy = 10)
      else
        RecordData(groupId, deviceId, 100,chargeStatus = 40, deliveredEnergy = -9)
    /*val request = HttpRequest(
      method = HttpMethods.POST,
      uri = urlToPostData,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        recordTemperature.toJson.toString
      )
    ) */

    sendJsonViaHttp(recordTemperature.toJson,urlToPostData,HttpMethods.POST)
    val nextParamters = if (parameters == "even") "odd" else "even"
    /*val responseFuture: Future[HttpResponse] =
      Http().singleRequest(request) */
    (RunSimulation(nextParamters), 2.seconds)
  }

  /*def sendStopNotificationToTwin(deviceId : String, groupId : String, urlToPostStopNoticiation : String) : Unit = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = urlToPostStopNoticiation,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        GroupIdDeviceId(groupId,deviceId).toJson.toString
      )
    )
  }*/

  def sendJsonViaHttp(json : JsValue, uri : String, method : HttpMethod, synchronous:Boolean = false)(implicit system: ActorSystem[_]) : Unit = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = uri,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        json.toString
      )
    )
    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(request)

    // make sure that message is delivered to
    if(synchronous) {
      implicit val executionContext = system.executionContext
      responseFuture.onComplete{
        case Failure(exception) => 
        case Success(result) => 
      }
    } 
    

  }

  def makeEntityId(groupId: String, deviceId: String): String = {
    groupId + "&&&" + deviceId
  }

}

package simulator

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
import akka.actor.typed.ActorRef

/**
  * actors of this type simulate hardware
  */
object DeviceSimulator {

  /**
    * messages that this actor can process
    */
  sealed trait Command

  /**
    * this message is sent to this actor in order to start the simulation of hardware
    */
  final case object StartSimulation                        extends Command

  /**
    * this message is sent to this actor in order to stop the simulation of hardware
    */
  final case class StopSimulation(replyTo : ActorRef[simulator.network.SimulatorHttpServer.SimulatorGuardian.ConfirmStop]) extends Command


  /**
    * this message is sent to this actor in order trigger a message to the associated twin. The triggered message tells the twin to stop tracking this hardware.
    */
  final case object UntrackDeviceAtTwin extends Command

  /**
    * this message is sent to this actor to trigger a new simulation step
    *
    * @param parameters
    */
  private[DeviceSimulator] final case class RunSimulation(parameters: String) extends Command

  /**
    * this message is sent by this actor to its twin in order to record its current state etc.
    *
    * @param groupId
    * @param deviceId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    */
  final case class RecordData(
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double
  )
  implicit val recordDataFormat = jsonFormat5(RecordData)

  /**
    * this message encodes a device identifier that uses a groupId and a deviceId to uniquely identify a hardware device
    *
    * @param groupId
    * @param deviceId
    */
  final case class GroupIdDeviceId(groupId:String, deviceId : String)
  implicit val groupIdDeviceIdFormat = jsonFormat2(GroupIdDeviceId)


  /**
    * returns an instance of a DeviceSimulator actor
    *
    * @param deviceId 
    * @param groupId
    * @param capacity the electric energy that this device can store
    * @param urlToPostData
    * @param urlToRequestTracking
    * @param urlToRequestStopTracking
    * @return
    */
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

  /**
    * 
    *
    * @param groupId
    * @param deviceId
    * @param capacity
    * @param chargeStatus the current chargeStatus of this hardware
    * @param routeToPostTemperature
    * @param urlToRequestTracking
    * @param urlToRequestUnTracking
    * @param simulationRunning
    * @return
    */
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
        case StartSimulation => 
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
        case StopSimulation(replyTo) => 
          // give all messages time to be processed by twin
          context.scheduleOnce(10.seconds, context.self,UntrackDeviceAtTwin) 
          replyTo ! simulator.network.SimulatorHttpServer.SimulatorGuardian.ConfirmStop(deviceId,groupId)
          Behaviors.same
        case UntrackDeviceAtTwin => 
          Behaviors.stopped{ () => { 
            implicit val executionContext = system.executionContext
            sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestUnTracking, HttpMethods.POST).onComplete{
              case Success(httpResponse) => 
              case Failure(exception) => context.scheduleOnce(2.seconds, context.self,UntrackDeviceAtTwin) // try again later
            }
          }
        }
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

  /**
    * defines the simulated behavior this hardware device
    *
    * @param groupId
    * @param deviceId
    * @param urlToPostData
    * @param parameters
    * @param system
    * @return (RunSimulation, FiniteDuration) the new message that should be processed by this actor after the returned behavior
    */
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
    sendJsonViaHttp(recordTemperature.toJson,urlToPostData,HttpMethods.POST)
    val nextParamters = if (parameters == "even") "odd" else "even"
    (RunSimulation(nextParamters), 2.seconds)
  }

  /**
    * sends a HTTP request with a JSON body
    *
    * @param json
    * @param uri
    * @param method
    * @param synchronous
    * @param system
    */
  def sendJsonViaHttp(json : JsValue, uri : String, method : HttpMethod)(implicit system: ActorSystem[_]) : Future[HttpResponse] = {
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
    responseFuture
  }

  /**
    * creates an identifier string
    *
    * @param groupId
    * @param deviceId
    * @return
    */
  def makeEntityId(groupId: String, deviceId: String): String = {
    groupId + "&&&" + deviceId
  }
}

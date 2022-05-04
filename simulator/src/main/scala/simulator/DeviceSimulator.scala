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

import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

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
  final case object StartSimulation extends Command

  /**
    * this message is sent to this actor in order to communicate a desired charge status for this hardware
    *
    * @param desiredChargeStatus
    */
  final case class SetDesiredChargeStatus(desiredChargeStatus: Double) extends Command

  /**
    * this message is sent to this actor in order to stop the simulation of hardware
    */
  final case class StopSimulation(replyTo: ActorRef[simulator.network.SimulatorHttpServer.SimulatorGuardian.ConfirmStop]) extends Command

  /**
    * this message is sent to this actor in order to stop the simulation of hardware
    */
  final case object StopSimulator extends Command

  

  /**
    * this message is sent to this actor in order trigger a message to the associated twin. The triggered message tells the twin to stop tracking this hardware.
    */
  final case class UntrackDeviceAtTwin(replyTo: ActorRef[simulator.network.SimulatorHttpServer.SimulatorGuardian.ConfirmStop]) extends Command

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
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime
  )

  implicit val localDateTimeFormat = new JsonFormat[LocalDateTime] {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    def write(x: LocalDateTime) = JsString(formatter.format(x))
    def read(value: JsValue) = value match {
      case JsString(x) => LocalDateTime.parse(x, formatter)
      case x => throw new RuntimeException(s"Unexpected type ${x.getClass.getName} when trying to parse LocalDateTime")
    }
  }
  implicit val recordDataFormat = jsonFormat6(RecordData)

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
      initialChargeStatus: Double,
      urlToPostData: String,
      urlToRequestTracking: String,
      urlToRequestStopTracking: String,
  ): Behavior[Command] = {
    getNewBehavior(groupId, deviceId, capacity, initialChargeStatus, initialChargeStatus, urlToPostData, urlToRequestTracking,urlToRequestStopTracking, false)
  }

  /**
    * 
    *
    * @param groupId
    * @param deviceId
    * @param capacity
    * @param chargeStatus the current chargeStatus of this hardware in range 0 (empty device) to 1 (full capacity used)
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
      desiredChargeStatus: Double, 
      routeToPostTemperature: String,
      urlToRequestTracking: String,
      urlToRequestUnTracking: String,
      simulationRunning: Boolean
  ): Behavior[Command] =
    Behaviors.receive[Command] { (context, message) =>
      implicit val system: ActorSystem[_] = context.system
      message match {
        case StartSimulation => 
          println(s"ALERT:Track Device request sent for $deviceId")
          implicit val executionContext = system.executionContext
            sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestTracking,HttpMethods.POST).onComplete{
              case Success(httpResponse) =>  println(s"ALERT:Device $deviceId successfully tracked: $httpResponse")
              case Failure(exception) => context.scheduleOnce(2.seconds, context.self,StartSimulation) // try again later
          }
          //sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestTracking,HttpMethods.POST)
          if (!simulationRunning) {
            context.self ! RunSimulation("even")
            getNewBehavior(
              groupId,
              deviceId,
              capacity,
              chargeStatus,
              desiredChargeStatus = chargeStatus,
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
          context.scheduleOnce(10.seconds, context.self,UntrackDeviceAtTwin(replyTo)) 
          //replyTo ! simulator.network.SimulatorHttpServer.SimulatorGuardian.ConfirmStop(deviceId,groupId)
          getNewBehavior(
              groupId,
              deviceId,
              capacity,
              chargeStatus,
              desiredChargeStatus = chargeStatus,
              routeToPostTemperature,
              urlToRequestTracking,
              urlToRequestUnTracking,
              false // do not process any RunSimulation messages and stop sending them
            )
          //Behaviors.same
        case StopSimulator =>
          println(s"STOPSIMULATOR RECEIVED AT $deviceId")
          Behaviors.stopped
        case SetDesiredChargeStatus(desiredChargeStatus) => 
          getNewBehavior(
              groupId,
              deviceId,
              capacity,
              chargeStatus,
              desiredChargeStatus,
              routeToPostTemperature,
              urlToRequestTracking,
              urlToRequestUnTracking,
              true
            )
        case UntrackDeviceAtTwin(replyTo) => 
          //println(s"UNTRACK DEVICE RECEIVED AT $deviceId")
          implicit val executionContext = system.executionContext
            sendJsonViaHttp(GroupIdDeviceId(groupId,deviceId).toJson,urlToRequestUnTracking, HttpMethods.POST).onComplete{
              case Success(httpResponse) =>  replyTo ! simulator.network.SimulatorHttpServer.SimulatorGuardian.ConfirmStop(deviceId,groupId) // confirm stop after untrack has been delivered
              case Failure(exception) => context.scheduleOnce(2.seconds, context.self,UntrackDeviceAtTwin(replyTo))
                                          // try again later
          }
          Behaviors.same
        case RunSimulation(parameters) => {
          if(simulationRunning) {
          val (message, delay, newChargeStatus) =
            simulateDevice(
              groupId,
              deviceId,
              routeToPostTemperature,
              parameters,
              capacity,
              chargeStatus,
              desiredChargeStatus,
            )
          context.scheduleOnce(delay, context.self, message)
          getNewBehavior(
              groupId,
              deviceId,
              capacity,
              newChargeStatus,
              desiredChargeStatus,
              routeToPostTemperature,
              urlToRequestTracking,
              urlToRequestUnTracking,
              true
            )
          } else {
            Behaviors.same
          }
          //Behaviors.same
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
      parameters: String,
      capacity: Double,
      chargeStatus: Double,
      desiredChargeStatus: Double,
  )(implicit system: ActorSystem[_]): (RunSimulation, FiniteDuration, Double) = {

    assert( chargeStatus>= 0.0 && chargeStatus <= 1.0, "ChargeStatus of DeviceSimulator is not in allowable range [0,1]: " + chargeStatus)
    assert( desiredChargeStatus>= 0.0 && desiredChargeStatus <= 1.0, "DesiredChargeStatus of DeviceSimulator is not in allowable range [0,1]: " + desiredChargeStatus)
    // this is the desired charge status that this device should reach and can be controlled externally
    //val desiredChargeStatus = 1.0
    val desiredEnergyStoredInHardware  = (desiredChargeStatus - chargeStatus) * capacity


    val now = LocalDateTime.now()
    val secondsOfDay = now.toLocalTime().toSecondOfDay()
  
    // local energy production has a random and a periodic part that simulates renewable energy production
    val maxAmplRandom = 0.01 * capacity
    val signRandom = if(math.random()>0.5) 1.0 else -1.0
    val random = math.random()*maxAmplRandom*signRandom
   
    val amplitudeOfLocalEnergyProduction = maxAmplRandom
    val periodicLocalEnergyProduction = amplitudeOfLocalEnergyProduction * (-1.0) * math.cos(secondsOfDay.toDouble/86400.0 * 2.0 * math.Pi)
    
    val localEnergyProduction =  periodicLocalEnergyProduction + random
    
    // Charging and uncharging are limited by technical constraints

    // how fast device can be charged
    val technicalChargeLimit = 0.1 * capacity 

    // how fast device can be uncharged
    val technicalUnChargeLimit = 2.0 * technicalChargeLimit

    // how much capacity is left
    val availableEnergyStorage = capacity - chargeStatus * capacity

    // amount of energy that could be potentially stored in hardware as limited by technical contraints
    val upperLimitEnergyStoredInHardware = math.min(technicalChargeLimit,availableEnergyStorage)

    // amount of energy that can be discharged as limited by technical contraints
    val lowerLimitEnergyStoredInHardware = math.max(-technicalUnChargeLimit, -chargeStatus * capacity)

    // the amount of energy stored in the hardware that best matches the desired charge status and technical constraints
    val energyStoredInLocalHardware = math.max(lowerLimitEnergyStoredInHardware,math.min(desiredEnergyStoredInHardware,upperLimitEnergyStoredInHardware))

    // energy produced locally in excess of what is stored in hardware is emitted to grid (positive sign).
    // energy stored in excess of local production is consumed from grid (negative sign)
    val energyDeposit = localEnergyProduction - energyStoredInLocalHardware

    // the new chargeStatus
    val newChargeStatus = (chargeStatus*capacity+energyStoredInLocalHardware)/capacity

    /*println("")
    println("DeviceId GroupId:" + deviceId + " " + groupId)
    println("secondsOfDay: " + secondsOfDay)
    println("randomPart " + random)
    println("periodicLocalEnergyProduction " + periodicLocalEnergyProduction)
    println("localEnergyProduction: " + localEnergyProduction)
    println("energyStoredInLocalHardware: " + energyStoredInLocalHardware)
    println("energyDeposit: " + energyDeposit)
    println("newChargeStatus: " + newChargeStatus) */

    /**
      * report activities and status to twin
      */
    val recordData = RecordData(groupId, deviceId, capacity , chargeStatus = newChargeStatus, deliveredEnergy = energyDeposit, now)
    sendJsonViaHttp(recordData.toJson,urlToPostData,HttpMethods.POST)

    /**
      * compute a message for self that drives the simulation forward
      */
    val nextParamters = if (parameters == "even") "odd" else "even"
    val delayNextMessage = 2.seconds
    (RunSimulation(nextParamters), delayNextMessage, newChargeStatus)
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

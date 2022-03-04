package twin

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.LoggerOps
import scala.concurrent.duration.FiniteDuration
import DeviceManager._

// clustersharding
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

//import akka.actor.ActorSystem
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.protobufv3.internal.Type
import akka.cluster.sharding.typed.scaladsl.Entity

import java.time.LocalDateTime
import spray.json.JsValue
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import scala.concurrent.Future
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.Http


import spray.json._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import scala.util.parsing.json._
import java.time.format.DateTimeFormatter
import akka.http.scaladsl.model.HttpMethods
import scala.util.Failure
import scala.util.Success
import akka.http.scaladsl.unmarshalling.Unmarshal
import twin.network.DeviceRoutes

/**
  * represents a group of Devices, i.e. digital twins of hardware. Thus, it represents a Virtual Power Plant.
  */
object DeviceGroup {

  /**
    * messages that this actor can process
    */
  sealed trait Command

  /**
    * a message that notifies this actor of a member that has stopped
    *
    * @param groupId
    * @param deviceId
    */
  final case class RequestUnTrackDevice(groupId: String, deviceId: String) extends Command

  /**
    * this message requests this actor to add a member
    *
    * @param deviceId
    * @param replyTo
    */
  final case class RequestTrackDevice(deviceId: String, replyTo: ActorRef[DeviceGroup.RespondTrackDevice]) extends Command

  /**
    * this message is sent as a positive response to a request to track this device
    *
    * @param deviceId
    */
  final case class RespondTrackDevice(deviceId: String)

  /**
    * this message requests this actor to return the state of all its members
    *
    * @param replyTo
    */
  final case class RequestAllData(replyTo: ActorRef[DeviceGroup.RespondAllData]) extends Command

  /**
    * this message is sent in response to a request to return the state of all its members
    *
    * @param requestId
    * @param data a map (deviceId -> read data) of the state of all members
    */
  final case class RespondAllData(requestId: Long, data: Map[String, DeviceGroupQuery.DataReading]) 
  
  /**
    * a message that requests to send a stop command to the hardware associated with the Device
    *
    * @param deviceId
    */
  final case class StopDevice(deviceId: String) extends Command

  final case class SetDesiredChargeStatus(deviceId: String, desiredChargeStatus: Double) extends Command

  final case class ResetPriority(deviceId: String) extends Command

  final case class DesiredTotalEnergyOutput(desiredTotalEnergyOutput: Double, currentEnergyOutput: Double) extends Command

  final case object AdjustTotalEnergyOutput extends Command

  /**
    * a message that requests to report the Data for a Device 
    *
    * @param deviceId
    */
  final case class RequestData(deviceId: String, replyTo: ActorRef[Device.RespondData]) extends Command

  /**
    *  a message that requests to record data from hardware in the associated Device
    *
    * @param deviceId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    */
  final case class RecordData( // TODO should be associated with a timestamp, should there be a dataformat declared in Device for what a DeviceData looks like? 
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime
  ) extends Command

  /**
    * states that this actor can assume
    */
  sealed trait State

  
  /**
    * state is defined by the current members, i.e. Devices, in this group and the desired total energy output
    *
    * @param registeredDevices the set of the PersistenceIds of the members of this group
    * @param desiredTotalEnergyOutput
    */
  final case class DeviceGroupState(registeredDevices: Set[String], desiredTotalEnergyOutput: Double) extends State

  /**
    * events that change the state of this actor
    */    
  sealed trait Event

  /**
    * a Device joins this group
    *
    * @param persistenceId
    * @param deviceId
    */
  final case class EventDeviceRegistered(persistenceId: String, deviceId: String) extends Event 
  
  /**
    * a Device leaves this group
    *
    * @param persistenceId
    * @param deviceId
    */                                                                             
  final case class EventDeviceUnRegistered(persistenceId: String, deviceId: String) extends Event

  final case class EventDesiredTotalEnergyOutputChanged(desiredTotalEnergyOutput:Double) extends Event

  /**
    * defines a type of an entity for cluster sharding
    */
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("DeviceGroup")

  /**
    * initializes Akka Cluster Sharding to be used for this actor type
    *
    * @param system
    */
  def initSharding(system: ActorSystem[_]): Unit = {
    val behaviorFactory: EntityContext[Command] => Behavior[Command] = { entityContext =>
      val persistenceId = PersistenceId(entityContext.entityTypeKey.name,entityContext.entityId)
      // also needs to use sharding to query Devices
      val sharding = ClusterSharding(system) 
      DeviceGroup(entityContext.entityId, persistenceId, sharding)
    }
    ClusterSharding(system).init(Entity(TypeKey)(behaviorFactory))
  }


  def apply(groupId: String, persistenceId: PersistenceId, sharding: ClusterSharding): Behavior[Command] = {
    /**
      * defines the behavior of this actor
      *
      * @return
      */
    def getNewBehaviour() : Behavior[Command] = {
      
      /**
        * returns an Effect that persists an Event which describes that a new Device has been registered.
        * also sends a reply
        * 
        * @param persistenceId
        * @param deviceId
        * @param replyTo confirmation of the registration is sent to this actor
        * @return
        */
      def registerDevice(persistenceId: PersistenceId, deviceId: String, replyTo: ActorRef[RespondTrackDevice]): Effect[Event, State] = {
        Effect.persist(EventDeviceRegistered(persistenceId.id, deviceId)).thenRun {
          state => replyTo ! RespondTrackDevice(deviceId) //DeviceManager.DeviceRegistered(deviceId)
        }
      }

      /**
        * returns an Effect that persists an Event which describes that a  Device has been unregistered
        *
        * @param persistenceId
        * @param deviceId
        * @return
        */
      def unregisterDevice(persistenceId: PersistenceId,deviceId: String): Effect[Event, State] = {
        Effect.persist(EventDeviceUnRegistered(persistenceId.id, deviceId))
      }


      Behaviors.setup { context =>
        /**
            * processes a Command and returns an Effect that defines which Events should be triggered and persisted
            *
            * @return
            */
        val commandHandler: (State, Command) => Effect[Event, State] = { (state, cmd) =>
          state match {
            case DeviceGroupState(devicesRegistered, desiredTotalEnergyOutput) =>
              cmd match {
                //case WrappedRequestTrackDevice(requestTrackDevice) => requestTrackDevice match {
                  case trackMsg @ RequestTrackDevice(deviceId, replyTo) =>
                    if (devicesRegistered(deviceId)) {
                      val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                      Effect.none.thenRun(state => replyTo ! RespondTrackDevice(deviceId)) //DeviceRegistered(deviceId))
                    } else {
                      context.log.info("Creating device actor for {}", trackMsg.deviceId)
                      val device = sharding.entityRefFor(Device.TypeKey,Device.makeEntityId(groupId, deviceId)) 
                      registerDevice(persistenceId, deviceId, replyTo)
                    }
                    /*case DeviceManager.RequestTrackDevice(gId, _, _) =>
                      context.log.warn2(
                        "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
                        gId,
                        groupId
                      )
                      Effect.none */
                  //}
                /*case WrappedRequestDeviceList(requestDeviceList) => requestDeviceList match {
                  case DeviceManager.RequestDeviceList(requestId, gId, replyTo) =>
                    if (gId == groupId) {
                      Effect.none.thenRun(state =>
                        replyTo ! RespondDeviceList(requestId, devicesRegistered)
                      )
                    } else {
                      Effect.none.thenRun(state => ())
                    }   
                }*/ 
                case RequestUnTrackDevice(_, deviceId) =>
                  context.log.info("Device actor for {} has been terminated", deviceId)
                  unregisterDevice(persistenceId, deviceId)
                case StopDevice(deviceId) => // TODO set priority to high so entity is not called anymore?
                  Effect.none.thenRun{ state => 
                    if(devicesRegistered.contains(deviceId)) {
                      val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                      device ! Device.StopDevice
                    }
                  }
                case SetDesiredChargeStatus(deviceId, desiredChargeStatus) => 
                  Effect.none.thenRun {
                    state => 
                    if(devicesRegistered.contains(deviceId)) {
                      println("SET DESIRED CHARGESTATUS AT GROUP " + deviceId)
                      val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                      device ! Device.SetDesiredChargeStatus(desiredChargeStatus, Device.Priorities.High)
                    }
                  }
                case DesiredTotalEnergyOutput(desiredTotalEnergyOutput, currentEnergyOutput) => 
                  Effect.persist(EventDesiredTotalEnergyOutputChanged(desiredTotalEnergyOutput)).thenRun { state =>
                    println("DESIRED ENERGY OUTPUT RECEIVED AT GROUP")
                    
                    /*final case class SendAdjustTotalEnergyOuputToGroup(groupId:String)
                    state => val auxActorRef = context.spawn(
                      Behaviors.receive[SendAdjustTotalEnergyOuputToGroup]((ctx, message) => 
                       
                        message match {
                        case SendAdjustTotalEnergyOuputToGroup(groupIdS) => 
                           println("DESIRED ENERGY OUTPUT RECEIVED AT AUX ACTOR")
                          val thisGroupEntity = sharding.entityRefFor(DeviceGroup.TypeKey, groupIdS)
                          thisGroupEntity ! AdjustTotalEnergyOutput
                          Behaviors.stopped
                        }), "auxActor"+java.util.UUID.randomUUID().toString())
                    import scala.concurrent.duration._
                    context.scheduleOnce(2.seconds, auxActorRef,SendAdjustTotalEnergyOuputToGroup(groupId)) */
                  }
                  
                  /*println("DESIRED TOTAL ENERGY OUTPUT AT GROUP " + desiredTotalEnergyOutput + " " + currentEnergyOutput)
                  Effect.none.thenRun{ state =>
                    val capacityOfDevice = 100.0
                    val totalCapacityOfVpp = devicesRegistered.size.toDouble * capacityOfDevice
                    val averageEnergyStoredInVpp = totalCapacityOfVpp * 0.5 // TODO Estimate can be improved
                    
                    val deltaEnergyOutput = desiredTotalEnergyOutput - currentEnergyOutput
                    
                    val targetTotalPercentageChangeOfChargeStatus = math.abs(desiredTotalEnergyOutput - currentEnergyOutput) / averageEnergyStoredInVpp * 100.0
                    val numberOfStepsToAchieveDesiredTotalEnergyOutput = 5.0

                    val targetPercentageChangeOfChargeStatusThisStep = targetTotalPercentageChangeOfChargeStatus / numberOfStepsToAchieveDesiredTotalEnergyOutput

                    val maxPercentageChangeOfChargeStatusPerStep = 10.0
                    val percentageChangeOfChargeStatusThisStep = math.min(targetPercentageChangeOfChargeStatusThisStep,maxPercentageChangeOfChargeStatusPerStep)

                    (desiredTotalEnergyOutput, currentEnergyOutput) match {
                    case (d,c) if (d > c) && math.abs(d-c) > 2.0 => 
                      for (dId <- devicesRegistered) {
                        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))
                        device ! Device.ModifyChargeStatus(-percentageChangeOfChargeStatusThisStep)
                      }
                    case (d,c) if (d < c) && math.abs(d-c) > 2.0 =>
                      for (dId <- devicesRegistered) {
                        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))
                        device ! Device.ModifyChargeStatus(+percentageChangeOfChargeStatusThisStep)
                      }
                    case _ => println("DID NOT DELIVER ANY MESSAGE TO DEVICE " + desiredTotalEnergyOutput + " " +currentEnergyOutput + " " + math.abs(desiredTotalEnergyOutput-currentEnergyOutput) )
                    }
                  } */
                case AdjustTotalEnergyOutput => Effect.none.thenRun{ state =>

                    println("ADJUST TOTAL ENERGY OUTPUT CALLED")


                    /*final case class SendAdjustTotalEnergyOuputToGroup(groupId:String)
                    val auxActorRef = context.spawn(
                      Behaviors.receive[SendAdjustTotalEnergyOuputToGroup]((ctx, message) => message match {
                        case SendAdjustTotalEnergyOuputToGroup(groupIdS) => 
                           println("DESIRED ENERGY OUTPUT RECEIVED AT AUX ACTOR")
                          val thisGroupEntity = sharding.entityRefFor(DeviceGroup.TypeKey, groupIdS)
                          thisGroupEntity ! AdjustTotalEnergyOutput
                          Behaviors.stopped
                        }), "auxActor"+java.util.UUID.randomUUID().toString())
                    import scala.concurrent.duration._
                    context.scheduleOnce(2.seconds, auxActorRef,SendAdjustTotalEnergyOuputToGroup(groupId))
                    */
            
                    implicit val localDateTimeFormat = new JsonFormat[LocalDateTime] {
                    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    def write(x: LocalDateTime) = JsString(formatter.format(x))
                    def read(value: JsValue) = value match {
                      case JsString(x) => LocalDateTime.parse(x, formatter)
                      case x => throw new RuntimeException(s"Unexpected type ${x.getClass.getName} when trying to parse LocalDateTime")
                    }
                  }
                                  /**
                    * represents the body of a http-request to obtain the energy deposited in a VPP in a timespan
                    *
                    * @param vppId
                    * @param before
                    * @param after
                    */
                  final case class EnergyDepositedRequest(vppId: String, before: LocalDateTime, after: LocalDateTime)
                  implicit val energyDepositedFormat = jsonFormat3(EnergyDepositedRequest)

                  final case class EnergyDepositedResponse(energyDeposited : Option[Double])
                  implicit val energyDepositedResponseFormat = jsonFormat1(EnergyDepositedResponse)

                  val now = LocalDateTime.now()
                  val before = now.minusSeconds(2)
                  val after = now.minusSeconds(12)
                  implicit val actorSystem = context.system
                  val readsideHost = context.system.settings.config.getConfig("readside").getString("host")
                  val readsidePort = context.system.settings.config.getConfig("readside").getString("port")
                  val routeToReadside = "http://" + readsideHost + ":" + readsidePort  + "/twin-readside"
                  val httpResponseFuture = sendHttpRequest(EnergyDepositedRequest(groupId,before,after).toJson,routeToReadside+"/energies",HttpMethods.GET)

                  implicit val executionContext = context.system.executionContext
                  httpResponseFuture.onComplete {
                    case Failure(exception) => 
                    case Success(httpResponse) =>  
                      val energyDepositedResponseF = Unmarshal(httpResponse).to[EnergyDepositedResponse]
                      energyDepositedResponseF.onComplete {
                        case Success(energyDepositedResponse) =>
                          energyDepositedResponse.energyDeposited match {
                            case Some(currentEnergyOutput) => 
                              val capacityOfDevice = 100.0
                              val totalCapacityOfVpp = devicesRegistered.size.toDouble * capacityOfDevice
                              val averageEnergyStoredInVpp = totalCapacityOfVpp * 0.5 // TODO Estimate can be improved
                              
                              val deltaEnergyOutput = desiredTotalEnergyOutput - currentEnergyOutput
                              
                              val targetTotalPercentageChangeOfChargeStatus = math.abs(desiredTotalEnergyOutput - currentEnergyOutput) / averageEnergyStoredInVpp * 100.0
                              val numberOfStepsToAchieveDesiredTotalEnergyOutput = 5.0

                              val targetPercentageChangeOfChargeStatusThisStep = targetTotalPercentageChangeOfChargeStatus / numberOfStepsToAchieveDesiredTotalEnergyOutput

                              val maxPercentageChangeOfChargeStatusPerStep = 10.0
                              val percentageChangeOfChargeStatusThisStep = math.min(targetPercentageChangeOfChargeStatusThisStep,maxPercentageChangeOfChargeStatusPerStep)

                              (desiredTotalEnergyOutput, currentEnergyOutput) match {
                              case (d,c) if (d > c) && math.abs(d-c) > 2.0 => 
                                for (dId <- devicesRegistered) {
                                  val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))
                                  device ! Device.ModifyChargeStatus(-percentageChangeOfChargeStatusThisStep)
                                }
                              case (d,c) if (d < c) && math.abs(d-c) > 2.0 =>
                                for (dId <- devicesRegistered) {
                                  val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))
                                  device ! Device.ModifyChargeStatus(+percentageChangeOfChargeStatusThisStep)
                                }
                              case _ => println("DID NOT DELIVER ANY MESSAGE TO DEVICE " + desiredTotalEnergyOutput + " " +currentEnergyOutput + " " + math.abs(desiredTotalEnergyOutput-currentEnergyOutput) )
                              }

                            case None => 
                          }
                        case Failure(exception) => 
                      }
                  }
                  //
                  
                  /*val currentEnergyOutput = 1.0

                    val capacityOfDevice = 100.0
                    val totalCapacityOfVpp = devicesRegistered.size.toDouble * capacityOfDevice
                    val averageEnergyStoredInVpp = totalCapacityOfVpp * 0.5 // TODO Estimate can be improved
                    
                    val deltaEnergyOutput = desiredTotalEnergyOutput - currentEnergyOutput
                    
                    val targetTotalPercentageChangeOfChargeStatus = math.abs(desiredTotalEnergyOutput - currentEnergyOutput) / averageEnergyStoredInVpp * 100.0
                    val numberOfStepsToAchieveDesiredTotalEnergyOutput = 5.0

                    val targetPercentageChangeOfChargeStatusThisStep = targetTotalPercentageChangeOfChargeStatus / numberOfStepsToAchieveDesiredTotalEnergyOutput

                    val maxPercentageChangeOfChargeStatusPerStep = 10.0
                    val percentageChangeOfChargeStatusThisStep = math.min(targetPercentageChangeOfChargeStatusThisStep,maxPercentageChangeOfChargeStatusPerStep)

                    (desiredTotalEnergyOutput, currentEnergyOutput) match {
                    case (d,c) if (d > c) && math.abs(d-c) > 2.0 => 
                      for (dId <- devicesRegistered) {
                        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))
                        device ! Device.ModifyChargeStatus(-percentageChangeOfChargeStatusThisStep)
                      }
                    case (d,c) if (d < c) && math.abs(d-c) > 2.0 =>
                      for (dId <- devicesRegistered) {
                        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))
                        device ! Device.ModifyChargeStatus(+percentageChangeOfChargeStatusThisStep)
                      }
                    case _ => println("DID NOT DELIVER ANY MESSAGE TO DEVICE " + desiredTotalEnergyOutput + " " +currentEnergyOutput + " " + math.abs(desiredTotalEnergyOutput-currentEnergyOutput) )
                    } */
                }
                case ResetPriority(deviceId) => 
                  Effect.none.thenRun{
                    state =>
                      if(devicesRegistered.contains(deviceId)) {
                        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                        device ! Device.ResetPriority
                      }
                  }
                case RequestData(deviceId,replyTo) => 
                  Effect.none.thenRun{
                    state => 
                      if(devicesRegistered.contains(deviceId)) {
                        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                        device ! Device.ReadData(replyTo)
                      }
                  }
                case RecordData(deviceId, capacity, chargeStatus, deliveredEnergy, deliveredEnergyDate) => 
                  if(devicesRegistered.contains(deviceId)) {
                    val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                    device ! Device.RecordData(capacity,chargeStatus,deliveredEnergy,deliveredEnergyDate)
                  } 
                  Effect.none
                case RequestAllData(replyTo) =>
                  
                      Effect.none.thenRun{ state =>
                        val deviceId2EntityRefSnapshot =  for {
                          dId <- devicesRegistered
                        } yield { dId -> sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))}
                        context.spawnAnonymous(
                        DeviceGroupQuery(deviceId2EntityRefSnapshot.toMap, requestId = 0,requester = replyTo, FiniteDuration(3, scala.concurrent.duration.SECONDS)))
                      }
                  
                   /* requestAllData match { 
                  case DeviceManager.RequestAllData(gId, replyTo) =>
                    if (gId == groupId) {
                      val deviceId2EntityRefSnapshot =  for {
                        dId <- devicesRegistered
                      } yield { dId -> sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))}
                                        
                      context.spawnAnonymous(
                        DeviceGroupQuery(deviceId2EntityRefSnapshot.toMap, requestId = 0,requester = replyTo, FiniteDuration(3, scala.concurrent.duration.SECONDS)))
                      Effect.none
                    } else
                      Effect.unhandled
                } */   
              }
          }
        }

        /**
        * processes persisted Events and may change the current State
        */
        val eventHandler: (State, Event) => State = {
          case (DeviceGroupState(devicesRegistered, desiredTotalEnergyOutput), EventDeviceRegistered(persistenceId, deviceId )) =>
            val newDeviceIdSet: Set[String] =
              devicesRegistered + deviceId 
            DeviceGroupState(newDeviceIdSet, desiredTotalEnergyOutput)
          case (DeviceGroupState(devicesRegistered,desiredTotalEnergyOutput),EventDeviceUnRegistered(persistenceId, deviceId)) =>
            val newDeviceIdSet: Set[String] =
              devicesRegistered - deviceId
            DeviceGroupState(newDeviceIdSet, desiredTotalEnergyOutput) 
          case(DeviceGroupState(devicesRegistered, desiredTotalEnergyOutput), EventDesiredTotalEnergyOutputChanged(newDesiredTotalEnergyOutput)) =>
            DeviceGroupState(devicesRegistered,newDesiredTotalEnergyOutput)
        }

        // create the behavior
        EventSourcedBehavior[Command, Event, State](
          persistenceId,
          emptyState = DeviceGroupState(Set.empty[String],0.0),
          commandHandler,
          eventHandler)
      }
    }
    getNewBehaviour()
  }


     /**
    * Sends a request asynchronously
    *
    * @param content the http-body 
    * @param uri the recipient address
    * @param method the http-method
    * @param system the actor system that handles the request
    * @return
    */
  private def sendHttpRequest(content: JsValue, uri: String, method: HttpMethod)(implicit system : ActorSystem[_]) : Future[HttpResponse] = {
    val request = HttpRequest(
              method = method,
              uri = uri, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                content.toString
              )
            )
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            responseFuture
}


}

package twin

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.Signal
import akka.actor.typed.PostStop
import akka.actor.typed.ActorRef

// cluster sharding
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity

import java.net.InetAddress

// persistence/event sourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.cluster.ddata.protobuf.msg.ReplicatorMessages.Read

// cqrs Projection
import akka.cluster.sharding.typed.scaladsl.EntityContext
import java.time.LocalDateTime
import akka.actor.SupervisorStrategy
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import twin.Device.Priority.High
import twin.Device.Priority.Low

import akka.persistence.typed.scaladsl.RetentionCriteria

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize



/** represents the digital twin of a device
  * 
  */
object Device {

  /**
    * messages that a Device can process
    */
  sealed trait Command

  /**
    * a message that requests to return the Device Data
    *
    * @param requestId
    * @param replyTo
    */
  final case class ReadData(
      replyTo: ActorRef[RespondData]
  ) extends Command with CborSerializable

  /**
    * a message that represents the data sent from hardware to a Device
    *
    * @param requestId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    * @param deliveredEnergyDate
    * @param replyTo
    */
  final case class RecordData(
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime,
  ) extends Command with CborSerializable

  /**
    * a message that is sent by a Device in response to a ReadData message
    *
    * @param requestId
    * @param deviceId
    * @param state
    * @param currentHost
    */
  final case class RespondData(
      deviceId: String,
      state: DeviceState,
      currentHost : Option[String] 
  ) extends CborSerializable

  /**
    * a request to stop the hardware associated with this Device
    */
  final case object StopDevice extends Command with CborSerializable

  /**
    * 
    *
    * @param desiredChargeStatus
    */
  final case class SetDesiredChargeStatus(desiredChargeStatus: Double, priority: Priority) extends Command with CborSerializable

  final case object ResetPriority extends Command with CborSerializable

  final case class DesiredDeltaEnergyOutput(deltaEnergyOutput: Double) extends Command with CborSerializable

  /**
    * states that a device can assume
    */
  sealed trait State extends CborSerializable

  /**
    * an instance of a state a device can assume
    *
    * @param capacity
    * @param lastChargeStatusReading
    * @param lastDeliveredEnergyReading // TODO remove?
    * @param lastTenDeliveredEnergyReadings,
    */
  final case class DeviceState(
    capacity: Double,
    lastTwoChargeStatusReadings: List[Option[Double]],
    lastDeliveredEnergyReading: Option[Double],
    lastTenDeliveredEnergyReadings: List[Option[Double]],
    priority: Priority,
  ) extends State

  /**
    * the events that change the state of this actor
    */
  sealed trait Event

  /**
    * recorded data is transmitted from the hardware to this digital twin
    *
    * @param persistenceId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    * @param deliveredEnergyDate
    */
  final case class EventDataRecorded(
    persistenceId: String,
    capacity: Double,
    chargeStatus: Double,
    deliveredEnergy: Double,
    deliveredEnergyDate: LocalDateTime,
  ) extends Event with CborSerializable

  /**
    * A Priority defines which messages a Device will process and which ones it will ignore
  */
  @JsonSerialize(`using` = classOf[Formats.DeviceFormats.PriorityJsonSerializer])
  @JsonDeserialize(`using` = classOf[Formats.DeviceFormats.PriorityJsonDeserializer])
  sealed trait Priority {
    def > (o: Priority) : Boolean = {
      this match {
        case Priority.High => if(o == Priority.Low) true else false 
        case Priority.Low => false
      }
    }
  }
  
  final object Priority {
    final case object High extends Priority 
    final case object Low extends Priority 
  }

  /**
    * triggered if the priority level is changed
    *
    * @param persistenceId
    * @param priority
    */
  final case class EventChargeStatusPrioritySet(persistenceId: String, priority: Priority) extends Event with CborSerializable

  
  /**
    * defines a type of an entity for cluster sharding
    */
  val TypeKey: EntityTypeKey[Device.Command] = EntityTypeKey[Device.Command]("Device")

  /**
    * tags that are used for devices in Akka Projection, each tag is associated with a dedicated sharded process
    */  
  val tags = Vector.tabulate(5)(i => s"device-tag-$i") // for projections

  /**
    * Initializes Akka Cluster Sharding to be used for this entity
    *
    * @param system
    */
  def initSharding(system: ActorSystem[_]): Unit = { 
    val behaviorFactory: EntityContext[Command] => Behavior[Command] = { entityContext =>
      val persistenceId = PersistenceId(entityContext.entityTypeKey.name,entityContext.entityId)
      val i = math.abs(persistenceId.id.hashCode % tags.size)
      val selectedTag = tags(i)
      Device(entityContext.entityId,persistenceId,selectedTag)
    }
    val shardRegionReference = ClusterSharding(system).init(Entity(TypeKey)(behaviorFactory))
  }

  /**
    * creates a string that uniquely identifies a Device entity 
    * across the cluster. This is used as an EntityId in cluster sharding
    *
    * @param groupId
    * @param deviceId
    * @return
    */
  def makeEntityId(
      groupId: String,
      deviceId: String
  ): String = {
    groupId + "&&&" + deviceId
  }

  /**
    * Extracts (groupId,deviceId) from a PersistenceId of the form "Device|groupId&&&deviceId"
    *
    * @param persistenceIdString of the form "Device|groupId&&&deviceId" (Device is the EntityTypeKey)
    * @return (groupId, deviceId)
    */
  def getGroupIdDeviceIdFromPersistenceIdString(persistenceIdString:String) :(String,String) = {
    persistenceIdString.split('|') match {
      case Array(deviceIdentifier,groupIdAndDeviceId) => groupIdAndDeviceId.split("&&&") match {
        case (Array(groupId,deviceId)) => (groupId,deviceId)
        case otherArray => throw new Exception("Cannot handle persistenceId " + persistenceIdString) 
      }
      case _ => throw new Exception("Cannot handle persistenceId " + persistenceIdString) 
    }
  }

  /**
    * creates a Device behavior from an entityId
    *
    * @param entityId
    * @param persistenceId
    * @param projectionTag
    * @return
    */
  def apply(entityId: String, persistenceId: PersistenceId,projectionTag: String): Behavior[Command] = {
    Device(entityId.split("&&&")(0), entityId.split("&&&")(1), persistenceId, projectionTag) 
  } 

  /**
    * creates a Device behavior from a groupId and deviceId
    *
    * @param groupId
    * @param deviceId
    * @param persistenceId
    * @param projectionTag
    * @return
    */
  def apply(groupId: String, deviceId: String, persistenceId: PersistenceId, projectionTag: String): Behavior[Command] = {
    def getNewBehaviour(groupId: String, deviceId: String, lastChargeStatusReading: Option[Double]): Behavior[Command] = {
      
      /**
        * helper function that returns an Effect that persists an EventDataRecorded event 
        *
        * @param persistenceId
        * @param cmd
        * @return
        */
      def recordData(persistenceId: String,cmd: RecordData): Effect[Event, State] = {
        Effect.persist(EventDataRecorded(persistenceId,cmd.capacity,cmd.chargeStatus,cmd.deliveredEnergy, cmd.deliveredEnergyDate))
      }

      /**
        * helper function that returns the name of the host that this Device currently runs on
        *
        * @return
        */
      def getHostName() : Option[String] = {
        Try {
            InetAddress.getLocalHost().getHostName()
        } match {
          case Failure(exception) => Some("Host could not be determined. Exception" + exception.getMessage())
          case Success(value) => Some(value)
        }
      }
      
      Behaviors.setup { context =>
        /**
          * processes a Command and returns an Effect that defines which Events should be triggered and persisted
          *
          * @return
          */
        val commandHandler : (State, Command) => Effect[Event, State] = { 
          (state, cmd) =>
            state match {
              case DeviceState(capacity,lastChargeStatusReading,lastDeliveredEnergyReading, lastTenDeliveredEnergyReadings, priority) =>
                cmd match {
                  case cmd: RecordData =>
                    recordData(persistenceId.id, cmd)
                  case ReadData(replyTo) =>
                    Effect.none.thenRun(state => state match {
                      case currentState : DeviceState => replyTo ! RespondData(deviceId,currentState,getHostName())
                    })
                  case StopDevice => Effect.none.thenRun{ 
                    state => 
                      implicit val system : ActorSystem[_] = context.system
                      HardwareCommunicator.sendDeviceCommand(HardwareCommunicator.StopHardwareDevice(groupId,deviceId))
                  }
                  case SetDesiredChargeStatus(desiredChargeStatus, priorityOfMessage) => 
                    priorityOfMessage match {
                      case pm if pm > priority => 
                        Effect.persist(EventChargeStatusPrioritySet(persistenceId.id,pm)).thenRun{
                          state : State => 
                          implicit val system : ActorSystem[_] = context.system
                          HardwareCommunicator.sendDeviceCommand(HardwareCommunicator.SetDesiredChargeStatusAtHardware(deviceId,groupId,desiredChargeStatus))
                        }
                      case pm if pm == priority => 
                        Effect.none.thenRun{
                          state : State => 
                          implicit val system : ActorSystem[_] = context.system
                          HardwareCommunicator.sendDeviceCommand(HardwareCommunicator.SetDesiredChargeStatusAtHardware(deviceId,groupId,desiredChargeStatus))
                        }
                      case pm => Effect.none
                    }
                  case ResetPriority => 
                    priority match {
                      case High => Effect.persist(EventChargeStatusPrioritySet(persistenceId.id,Low))
                      case Low => Effect.none
                    }
                  case DesiredDeltaEnergyOutput(deltaEnergyOutput) => 
                    priority match {
                      case High => Effect.none
                      case Low => Effect.none.thenRun { state => (lastChargeStatusReading, lastTenDeliveredEnergyReadings.reverse.head) match {
                        case (List(Some(lastButOneChargeStatus),Some(lastChargeStatus)), Some(lastDeliveredEnergyReading)) => 
                            def computeDesiredChargeStatus(deltaEnergyOutput: Double, lastButOneChargeStatus: Double, lastChargeStatus: Double, lastDeliveredEnergyReading: Double) : Double = {
                              val chargeStatusChangesAtZeroLocalProduction =  -deltaEnergyOutput/capacity 
                              val chargeStatusChangesCorrectionForLocalEnergyProduction = lastChargeStatus - lastButOneChargeStatus                           
                              val upperBound = 1.0
                              val lowerBound = 0.0
                              val desiredChargeStatusTmp = lastChargeStatus + chargeStatusChangesAtZeroLocalProduction + chargeStatusChangesCorrectionForLocalEnergyProduction  
                              val desiredChargeStatus = math.min(math.max(lowerBound,desiredChargeStatusTmp),upperBound)
                              desiredChargeStatus
                            } 
                                                         
                            implicit val system: ActorSystem[_] = context.system
                            HardwareCommunicator.sendDeviceCommand(HardwareCommunicator.SetDesiredChargeStatusAtHardware(deviceId,groupId,computeDesiredChargeStatus(deltaEnergyOutput,lastButOneChargeStatus, lastChargeStatus,lastDeliveredEnergyReading)))
                        case (List(_,_),_) => context.log.info(s"Cannot handle DesiredDeltaEnergyOutput message at $deviceId since prerequisites are not met ${(lastChargeStatusReading, lastTenDeliveredEnergyReadings.reverse.head).toString()}")
                    }
                  }
                  }
                }
            }
        }

        /**
          * processes persisted Events and may change the current State
          */
        val eventHandler: (State, Event) => State = { (state, event) =>
          (state,event) match {
            case (s: DeviceState, e: EventDataRecorded) => 
              val newLastTenDeliveredEnergyReadings = (Some(e.deliveredEnergy) :: (s.lastTenDeliveredEnergyReadings drop 1 ).reverse).reverse
              val newLastTwoChargeStatusReadings = s.lastTwoChargeStatusReadings.reverse.head :: List(Some(e.chargeStatus))
              DeviceState(e.capacity,newLastTwoChargeStatusReadings,Some(e.deliveredEnergy),newLastTenDeliveredEnergyReadings, s.priority)
            case(s: DeviceState, e: EventChargeStatusPrioritySet) =>
              DeviceState(s.capacity, s.lastTwoChargeStatusReadings, s.lastDeliveredEnergyReading, s.lastTenDeliveredEnergyReadings, e.priority)
          }
        } //
        

        // returns the behavior
        EventSourcedBehavior[Command, Event, State](persistenceId,emptyState = DeviceState(0,List(None,None),None,List[Option[Double]](None,None,None,None,None,None,None,None,None,None),Priority.Low),commandHandler,eventHandler)
        .withTagger(_ => Set(projectionTag)) 
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2))
        //.withDeleteEventsOnSnapshot)
      }
    }
    getNewBehaviour(groupId,deviceId,None)
  }

  /**
    * interface to the hardware device that the Device represents
    */
  object HardwareCommunicator {

    import spray.json._
    import spray.json.DefaultJsonProtocol._
    import spray.json.JsValue
    import akka.http.scaladsl.model.HttpMethod
    import akka.http.scaladsl.model.HttpRequest
    import akka.http.scaladsl.model.HttpEntity
    import akka.http.scaladsl.model.HttpMethods
    import scala.concurrent.Future
    import akka.http.scaladsl.model.HttpResponse
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.ContentTypes
    
    /**
      * messages that can be sent to hardware
      */
    sealed trait HardwareDeviceCommand

    /**
      * stop a hardware device
      *
      * @param groupId
      * @param deviceId
      */
    final case class StopHardwareDevice(groupId: String, deviceId: String) extends HardwareDeviceCommand
    implicit val stopSimulationFormat = jsonFormat2(StopHardwareDevice)

    final case class SetDesiredChargeStatusAtHardware(deviceId: String, groupId: String, desiredChargeStatus: Double) extends HardwareDeviceCommand
    implicit val setDesiredChargeStatusAtHardwareFormat = jsonFormat3(SetDesiredChargeStatusAtHardware)

    /**
      * sends a command to the hardware
      *
      * @param deviceCommand
      * @param system
      */
    def sendDeviceCommand(deviceCommand : HardwareDeviceCommand)(implicit system: ActorSystem[_]) : Unit = {
      def sendJsonViaHttp(json : JsValue, uri : String, method : HttpMethod)(implicit system: ActorSystem[_]) = {
        val request = HttpRequest(method = method,uri = uri,entity = HttpEntity(contentType = ContentTypes.`application/json`,json.toString))
        val responseFuture: Future[HttpResponse] = Http().singleRequest(request)
      }

      val simulatorConfig = system.settings.config.getConfig("simulator")
      val host = simulatorConfig.getString("host")
      val port = simulatorConfig.getString("port")

      deviceCommand match {
        case stopSimulation : StopHardwareDevice => 
          val route = "http://" + host + ":" + port + "/simulator" + "/stop"
          sendJsonViaHttp(stopSimulation.toJson, route,HttpMethods.POST)
        case setDesiredChargeStatusAtHardware : SetDesiredChargeStatusAtHardware =>
          val route = "http://" + host + ":" + port + "/simulator" + "/charge-status" 
          sendJsonViaHttp(setDesiredChargeStatusAtHardware.toJson, route,HttpMethods.POST)
      }
    }
  }
}


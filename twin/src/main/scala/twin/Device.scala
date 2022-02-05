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


/** represents the digital twin of a device
  * 
  */
object Device {

  /**
    * defines a type of an entity for cluster sharding
    */
  val TypeKey: EntityTypeKey[Device.Command] =
    EntityTypeKey[Device.Command]("Device")

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
    ClusterSharding(system).init(Entity(TypeKey)(behaviorFactory))
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
      Behaviors.setup { context =>
        val commandHandler
            : (State, Command) => Effect[Event, State] = { // TO getCommandHandlerfunction that predefines deviceID, so it does not need to be build every time but can access context
          (state, cmd) =>
            state match {
              case DeviceState(capacity,lastChargeStatusReading,lastDeliveredEnergyReading) =>
                cmd match {
                  case cmd: RecordData =>
                    context.log.info(
                      s"ALERT: Data recorded for device ${deviceId}"
                    )
                    recordData(persistenceId.id, cmd)
                  /*case cmd: RecordTemperature =>
                    context.log.info(
                      s"ALERT: Temperature recorded for device ${deviceId}"
                    )
                    recordTemperature(persistenceId.id, cmd)*/
                  case ReadTemperature(id, replyTo) =>
                    Effect.none.thenRun(state =>
                      replyTo ! RespondTemperature(
                        id,
                        deviceId,
                        lastChargeStatusReading,
                        getHostName
                      )
                    )
                  case Passivate => Effect.stop
                  case StopDevice => Effect.none.thenRun{
                    state => 
                      implicit val system : ActorSystem[_] = context.system
                      sendDeviceCommand(StopSimulation(groupId,deviceId))
                  }
                  case GetHostName(replyTo) =>
                    Effect.none.thenRun(state => {
                      context.log.info(
                        s"ALERT: device $deviceId is running on host ${InetAddress.getLocalHost().getHostName()}"
                      )
                      replyTo ! NetworkActor.HostName(
                        InetAddress
                          .getLocalHost()
                          .getHostName()
                      )
                    })
                }
            }
        }

        val eventHandler: (State, Event) => State = { (state, event) =>
          (state,event) match {
            /*case e: EventTemperatureRecorded =>
                StateTemperature(Some(e.temperature)) */
            case (s: DeviceState,e: EventDataRecorded) => 
              println("EVENT OCCURED!!")
              DeviceState(e.capacity,Some(e.chargeStatus),Some(e.deliveredEnergy))
            case _ => DeviceState(0,None,None)

          }
        }

        context.system.log
          .info("TAGDEVICE " + projectionTag + " TAGDEVICE")

        EventSourcedBehavior[Command, Event, State](
          persistenceId,
          emptyState = DeviceState(0,None,None),
          commandHandler,
          eventHandler
        ).withTagger(_ =>
          Set(projectionTag)
        ) 
      }
  }
    getNewBehaviour(groupId,deviceId,None)
  }

  

  sealed trait State
  // State
  /*final case class StateTemperature(
      lastTemperatureReading: Option[Double]
  ) extends State */

  final case class DeviceState(
    capacity: Double,
    lastChargeStatusReading: Option[Double],
    lastDeliveredEnergyReading: Option[Double]
  ) extends State

  sealed trait Event

  // Event
  /*final case class EventTemperatureRecorded(
      persistenceId: String,
      temperature: Double
  ) extends Event  */

  final case class EventDataRecorded(
    persistenceId: String,
    capacity: Double,
    chargeStatus: Double,
    deliveredEnergy: Double,
    deliveredEnergyDate: LocalDateTime,
  ) extends Event

  /*private def recordTemperature(
      persistenceId: String,
      cmd: RecordTemperature
  ): Effect[Event, State] = {
    Effect
      .persist(
        EventTemperatureRecorded(persistenceId, cmd.value)
      )
      .thenRun(state => cmd.replyTo ! TemperatureRecorded(cmd.requestId))
  } */


  private def recordData(
      persistenceId: String,
      cmd: RecordData
  ): Effect[Event, State] = {
    Effect
      .persist(
        //EventTemperatureRecorded(persistenceId, cmd.capacity)
        EventDataRecorded(persistenceId,cmd.capacity,cmd.chargeStatus,cmd.deliveredEnergy, cmd.deliveredEnergyDate)
      )
      .thenRun(state => cmd.replyTo ! DataRecorded(cmd.requestId) )
  }


  sealed trait Command
  final case class ReadTemperature(
      requestId: Long,
      replyTo: ActorRef[RespondTemperature]
  ) extends Command

  final case class RespondTemperature(
      requestId: Long,
      deviceId: String,
      value: Option[Double],
      currentHost : Option[String]
  )

  final case class RecordData(
      requestId: Long,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime,
      replyTo: ActorRef[DataRecorded]
  ) extends Command

  //final case class TemperatureRecorded(requestId: Long)

  final case class DataRecorded(requestId: Long)

  case object Passivate extends Command

  final case class GetHostName(
      replyTo: ActorRef[NetworkActor.HostName]
  ) extends Command

  private def getHostName : Option[String] = {
    Try {
      InetAddress.getLocalHost().getHostName()
    } match {
      case Failure(exception) => Some("Host could not be determined. Exception" + exception.getMessage())
      case Success(value) => Some(value)
    }
    
  }

  final case object StopDevice extends Command

  

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
    // messages for devices, TODO problem these messages are always dependent on how communication to devices works -> kapseln in Klasse/Netzwerk komponente?
    
    sealed trait DeviceCommand
    final case class StopSimulation(groupId:String, deviceId : String) extends DeviceCommand
    implicit val stopSimulationFormat = jsonFormat2(StopSimulation)

  
    private def sendDeviceCommand(deviceCommand : DeviceCommand)(implicit system: ActorSystem[_]) : Unit = {
      deviceCommand match {
        case stopSimulation : StopSimulation => 
          val simulatorConfig = system.settings.config.getConfig("simulator")
          val host = simulatorConfig.getString("host")
          val port = simulatorConfig.getString("port")
          val routeToSimulator = "http://" + host + ":" + port + "/stop"
          sendJsonViaHttp(stopSimulation.toJson, routeToSimulator,HttpMethods.POST)
      }
      
    }
    

   private def sendJsonViaHttp(json : JsValue, uri : String, method : HttpMethod)(implicit system: ActorSystem[_]) = {
    val request = HttpRequest(
      method = method,
      uri = uri,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        json.toString
      )
    )
    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(request)
  }
}

/*class Device(
    context: ActorContext[Device.Command],
    groupId: String,
    deviceId: String
) extends AbstractBehavior[Device.Command](context) {
  import Device._

  var lastTemperatureReading: Option[Double] = None
  context.log.info2(
    "Device actor {}-{} started",
    groupId,
    deviceId
  )

  override def onMessage(
      msg: Command
  ): Behavior[Command] = {
    msg match {
      case RecordTemperature(id, value, replyTo) =>
        context.log.info2(
          "Recorded temperature reading {} with {}",
          value,
          id
        )
        lastTemperatureReading = Some(value)
        replyTo ! TemperatureRecorded(id)
        this

      case ReadTemperature(id, replyTo) =>
        replyTo ! RespondTemperature(
          id,
          deviceId,
          lastTemperatureReading
        )
        this

      case Passivate =>
        Behaviors.stopped

      case GetHostName(replyTo) =>
        context.log.info(
          s"ALERT: device $deviceId is running for user ${InetAddress.getLocalHost().getHostName()}"
        )
        replyTo ! NetworkActor.HostName(
          InetAddress.getLocalHost().getHostName()
        )
        this
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = { case PostStop =>
    context.log.info2(
      "Device actor {}-{} stopped",
      groupId,
      deviceId
    )
    this
  }

} */

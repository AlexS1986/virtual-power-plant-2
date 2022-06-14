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
import akka.persistence.typed.scaladsl.RetentionCriteria
//import twin.network.DeviceRoutes

/** represents a group of Devices, i.e. digital twins of hardware. Thus, it represents a Virtual
  * Power Plant.
  */
object DeviceGroup {

  /** messages that this actor can process
    */
  sealed trait Command

  /** a message that notifies this actor of a member that has stopped
    *
    * @param groupId
    * @param deviceId
    */
  final case class RequestUnTrackDevice(deviceId: String) extends Command with CborSerializable

  /** this message requests this actor to add a member
    *
    * @param deviceId
    * @param replyTo
    */
  final case class RequestTrackDevice(
      deviceId: String,
      replyTo: ActorRef[DeviceGroup.RespondTrackDevice]
  ) extends Command with CborSerializable

  /** this message is sent as a positive response to a request to track this device
    *
    * @param deviceId
    */
  final case class RespondTrackDevice(deviceId: String) extends CborSerializable

  /** this message requests this actor to return the state of all its members
    *
    * @param replyTo
    */
  final case class RequestAllData(replyTo: ActorRef[DeviceGroup.RespondAllData]) extends Command with CborSerializable

  /** this message is sent in response to a request to return the state of all its members
    *
    * @param requestId
    * @param data
    *   a map (deviceId -> read data) of the state of all members
    */
  final case class RespondAllData(data: Map[String, DeviceGroupQuery.ChargeStatusReading]) extends CborSerializable

  /** a message that requests to send a stop command to the hardware associated with the Device
    *
    * @param deviceId
    */
  final case class StopDevice(deviceId: String) extends Command with CborSerializable

  /** set the desired charge status for a specific Device
    *
    * @param deviceId
    * @param desiredChargeStatus
    */
  final case class SetDesiredChargeStatus(deviceId: String, desiredChargeStatus: Double)
      extends Command with CborSerializable

  /** reset the priority of accepted messages for a specific Device to the lowest level
    *
    * @param deviceId
    */
  final case class ResetPriority(deviceId: String) extends Command with CborSerializable

  /** sets the desired total energy output and relaxation parameter for this DeviceGroup
    *
    * @param desiredTotalEnergyOutput
    * @param relaxationParameter
    */
  final case class DesiredTotalEnergyOutput(
      desiredTotalEnergyOutput: Double,
      relaxationParameter: Double
  ) extends Command with CborSerializable

  /** requests to adjust the total energy output to the desired value
    */
  final case object AdjustTotalEnergyOutput extends Command with CborSerializable

  /** a message that requests to report the Data for a Device
    *
    * @param deviceId
    */
  final case class RequestData(deviceId: String, replyTo: ActorRef[Device.RespondData])
      extends Command with CborSerializable

  /** a message that requests to record data from hardware in the associated Device
    *
    * @param deviceId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    */
  final case class RecordData(
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime
  ) extends Command with CborSerializable

  /** states that this actor can assume
    */
  sealed trait State extends CborSerializable

  /** state is defined by the current members, i.e. Devices, in this group and the desired total
    * energy output
    *
    * @param registeredDevices
    *    the set of the PersistenceIds of the members of this group
    * @param desiredTotalEnergyOutput
    */
  final case class DeviceGroupState(
      registeredDevices: Set[String],
      desiredTotalEnergyOutput: Double,
      relaxationParameter: Double
  ) extends State

  /** events that change the state of this actor
    */
  sealed trait Event

  /** a Device joins this group
    *
    * @param persistenceId
    * @param deviceId
    */
  final case class EventDeviceRegistered(persistenceId: String, deviceId: String) extends Event with CborSerializable

  /** a Device leaves this group
    *
    * @param persistenceId
    * @param deviceId
    */
  final case class EventDeviceUnRegistered(persistenceId: String, deviceId: String) extends Event with CborSerializable

  final case class EventDesiredTotalEnergyOutputChanged(
      desiredTotalEnergyOutput: Double,
      relaxationParameter: Double
  ) extends Event with CborSerializable

  /** defines a type of an entity for cluster sharding
    */
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("DeviceGroup")

  /** initializes Akka Cluster Sharding to be used for this actor type
    *
    * @param system
    */
  def initSharding(system: ActorSystem[_]): Unit = {
    val behaviorFactory: EntityContext[Command] => Behavior[Command] = { entityContext =>
      val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
      // also needs to use sharding to query Devices
      val sharding = ClusterSharding(system)
      DeviceGroup(entityContext.entityId, persistenceId, sharding)
    }
    ClusterSharding(system).init(Entity(TypeKey)(behaviorFactory))
  }

  def apply(
      groupId: String,
      persistenceId: PersistenceId,
      sharding: ClusterSharding
  ): Behavior[Command] = {

    /** defines the behavior of this actor
      *
      * @return
      */
    def getNewBehaviour(): Behavior[Command] = {

      /** returns an Effect that persists an Event which describes that a new Device has been
        * registered. also sends a reply
        *
        * @param persistenceId
        * @param deviceId
        * @param replyTo
        *   confirmation of the registration is sent to this actor
        * @return
        */
      def registerDevice(
          persistenceId: PersistenceId,
          deviceId: String,
          replyTo: ActorRef[RespondTrackDevice]
      ): Effect[Event, State] = {
        Effect.persist(EventDeviceRegistered(persistenceId.id, deviceId)).thenRun { state =>
          replyTo ! RespondTrackDevice(deviceId)
        }
      }

      /** returns an Effect that persists an Event which describes that a Device has been
        * unregistered
        *
        * @param persistenceId
        * @param deviceId
        * @return
        */
      def unregisterDevice(persistenceId: PersistenceId, deviceId: String): Effect[Event, State] = {
        Effect.persist(EventDeviceUnRegistered(persistenceId.id, deviceId))
      }

      Behaviors.setup { context =>
        /** processes a Command and returns an Effect that defines which Events should be triggered
          * and persisted
          *
          * @return
          */
        val commandHandler: (State, Command) => Effect[Event, State] = { (state, cmd) =>
          state match {
            case DeviceGroupState(
                  devicesRegistered,
                  desiredTotalEnergyOutput,
                  relaxationParameter
                ) =>
              cmd match {
                case trackMsg @ RequestTrackDevice(deviceId, replyTo) =>
                  if (devicesRegistered(deviceId)) {
                    Effect.none.thenRun(state => replyTo ! RespondTrackDevice(deviceId))
                  } else {
                    context.log.info("Creating device actor for {}", trackMsg.deviceId)
                    registerDevice(persistenceId, deviceId, replyTo)
                  }
                case RequestUnTrackDevice(deviceId) =>
                  context.log.info("Device actor for {} has been terminated", deviceId)
                  unregisterDevice(persistenceId, deviceId)
                case StopDevice(deviceId) =>
                  Effect.none.thenRun { state =>
                    if (devicesRegistered.contains(deviceId)) {
                      val device = sharding.entityRefFor(
                        Device.TypeKey,
                        Device.makeEntityId(groupId, deviceId)
                      )
                      device ! Device.StopDevice
                    }
                  }
                case SetDesiredChargeStatus(deviceId, desiredChargeStatus) =>
                  Effect.none.thenRun { state =>
                    if (devicesRegistered.contains(deviceId)) {
                      val device = sharding.entityRefFor(
                        Device.TypeKey,
                        Device.makeEntityId(groupId, deviceId)
                      )
                      device ! Device.SetDesiredChargeStatus(
                        desiredChargeStatus,
                        Device.Priority.High
                      )
                    }
                  }
                case DesiredTotalEnergyOutput(desiredTotalEnergyOutput, relaxationParameter) =>
                  Effect
                    .persist(
                      EventDesiredTotalEnergyOutputChanged(
                        desiredTotalEnergyOutput,
                        relaxationParameter
                      )
                    )
                    .thenRun { state => }
                case AdjustTotalEnergyOutput =>
                  Effect.none.thenRun { state =>
                    import Formats.localDateTimeFormat
                    import Formats.{
                      energyDepositedFormat,
                      energyDepositedResponseFormat,
                      EnergyDepositedRequest,
                      EnergyDepositedResponse
                    }
                    val now    = LocalDateTime.now()
                    val before = now.minusSeconds(2)
                    val after  = now.minusSeconds(12)
                    val readsideHost =
                      context.system.settings.config.getConfig("readside").getString("host")
                    val readsidePort =
                      context.system.settings.config.getConfig("readside").getString("port")
                    val routeToReadside =
                      "http://" + readsideHost + ":" + readsidePort + "/twin-readside"

                    implicit val actorSystem = context.system
                    val httpResponseFuture = sendHttpRequest(
                      EnergyDepositedRequest(groupId, before, after).toJson,
                      routeToReadside + "/energies",
                      HttpMethods.GET
                    )

                    implicit val executionContext = context.system.executionContext
                    httpResponseFuture.onComplete {
                      case Failure(exception) =>
                      case Success(httpResponse) =>
                        val energyDepositedResponseF =
                          Unmarshal(httpResponse).to[EnergyDepositedResponse]
                        energyDepositedResponseF.onComplete {
                          case Success(energyDepositedResponse) =>
                            energyDepositedResponse.energyDeposited match {
                              case Some(currentEnergyOutput) =>
                                if (!devicesRegistered.isEmpty && relaxationParameter != 0.0) {
                                  val newEnergyOutput =
                                    (desiredTotalEnergyOutput - currentEnergyOutput) / relaxationParameter + currentEnergyOutput
                                  val deltaEnergyOutput = newEnergyOutput - currentEnergyOutput
                                  val deltaEnergyOutputPerDevice =
                                    deltaEnergyOutput / devicesRegistered.size.toDouble

                                  for (dId <- devicesRegistered) {
                                    val device = sharding.entityRefFor(
                                      Device.TypeKey,
                                      Device.makeEntityId(groupId, dId)
                                    )
                                    device ! Device.DesiredDeltaEnergyOutput(
                                      deltaEnergyOutputPerDevice
                                    )
                                  }
                                }
                              case None =>
                                context.log.error(
                                  "Request to readside failed. No current energy output recorded "
                                )
                            }
                          case Failure(exception) =>
                            context.log.error(
                              "Request to readside failed. Could not determine current total energy output."
                            )
                        }
                    }
                  }
                case ResetPriority(deviceId) =>
                  Effect.none.thenRun { state =>
                    if (devicesRegistered.contains(deviceId)) {
                      val device = sharding.entityRefFor(
                        Device.TypeKey,
                        Device.makeEntityId(groupId, deviceId)
                      )
                      device ! Device.ResetPriority
                    }
                  }
                case RequestData(deviceId, replyTo) =>
                  Effect.none.thenRun { state =>
                    if (devicesRegistered.contains(deviceId)) {
                      val device = sharding.entityRefFor(
                        Device.TypeKey,
                        Device.makeEntityId(groupId, deviceId)
                      )
                      device ! Device.ReadData(replyTo)
                    }
                  }
                case RecordData(
                      deviceId,
                      capacity,
                      chargeStatus,
                      deliveredEnergy,
                      deliveredEnergyDate
                    ) =>
                  if (devicesRegistered.contains(deviceId)) {
                    val device =
                      sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                    device ! Device.RecordData(
                      capacity,
                      chargeStatus,
                      deliveredEnergy,
                      deliveredEnergyDate
                    )
                  }
                  Effect.none
                case RequestAllData(replyTo) =>
                  Effect.none.thenRun { state =>
                    val deviceId2EntityRefSnapshot = for {
                      dId <- devicesRegistered
                    } yield {
                      dId -> sharding.entityRefFor(
                        Device.TypeKey,
                        Device.makeEntityId(groupId, dId)
                      )
                    }
                    context.spawnAnonymous(
                      DeviceGroupQuery(
                        deviceId2EntityRefSnapshot.toMap,
                        requester = replyTo,
                        FiniteDuration(3, scala.concurrent.duration.SECONDS)
                      )
                    )
                  }
              }
          }
        }

        /** processes persisted Events and may change the current State
          */
        val eventHandler: (State, Event) => State = {
          case (
                DeviceGroupState(devicesRegistered, desiredTotalEnergyOutput, relaxationParameter),
                EventDeviceRegistered(persistenceId, deviceId)
              ) =>
            val newDeviceIdSet: Set[String] =
              devicesRegistered + deviceId
            DeviceGroupState(newDeviceIdSet, desiredTotalEnergyOutput, relaxationParameter)
          case (
                DeviceGroupState(devicesRegistered, desiredTotalEnergyOutput, relaxationParameter),
                EventDeviceUnRegistered(persistenceId, deviceId)
              ) =>
            val newDeviceIdSet: Set[String] =
              devicesRegistered - deviceId
            DeviceGroupState(newDeviceIdSet, desiredTotalEnergyOutput, relaxationParameter)
          case (
                DeviceGroupState(devicesRegistered, desiredTotalEnergyOutput, relaxationParameter),
                EventDesiredTotalEnergyOutputChanged(
                  newDesiredTotalEnergyOutput,
                  newRelaxationParameter
                )
              ) =>
            DeviceGroupState(devicesRegistered, newDesiredTotalEnergyOutput, newRelaxationParameter)
        }

        // create the behavior
        EventSourcedBehavior[Command, Event, State](
          persistenceId,
          emptyState = DeviceGroupState(Set.empty[String], 0.0, 1.0),
          commandHandler,
          eventHandler
        )
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2))
        //.withDeleteEventsOnSnapshot)
      }
    }
    getNewBehaviour()
  }

  /** Sends a request asynchronously
    *
    * @param content
    *   the http-body
    * @param uri
    *   the recipient address
    * @param method
    *   the http-method
    * @param system
    *   the actor system that handles the request
    * @return
    */
  private def sendHttpRequest(content: JsValue, uri: String, method: HttpMethod)(implicit
      system: ActorSystem[_]
  ): Future[HttpResponse] = {
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

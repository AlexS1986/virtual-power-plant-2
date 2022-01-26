package com.example

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

// DeviceGroup

object DeviceGroup {
  /*def apply(groupId: String): Behavior[Command] =
    Behaviors.setup(context => new DeviceGroup(context, groupId)) */

  //

  def apply(
      groupId: String,
      persistenceId: PersistenceId,
      sharding: ClusterSharding,
      //deviceIdToActor: Map[String, EntityRef[Device.Command]]
  ): Behavior[Command] =
    getNewBehaviour(groupId, persistenceId, sharding)

  trait Command

  final case class DeviceTerminated(
      //device: EntityRef[Device.Command],
      groupId: String,
      deviceId: String
  ) extends Command

  val TypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("DeviceGroup")

  def initSharding(system: ActorSystem[_]): Unit = {
    val behaviorFactory: EntityContext[Command] => Behavior[Command] = { entityContext =>
      val persistenceId = PersistenceId(
        entityContext.entityTypeKey.name,
        entityContext.entityId
      )
      
      val sharding = ClusterSharding(system)
      //val deviceIdToActor =
      //  Map.empty[String, EntityRef[Device.Command]]
      DeviceGroup(entityContext.entityId, persistenceId, sharding)
    }
    ClusterSharding(system).init(Entity(TypeKey)(behaviorFactory))
  }

  private def getNewBehaviour(
      groupId: String,
      persistenceId: PersistenceId,
      sharding: ClusterSharding,
      //deviceIdToActor: Map[String, EntityRef[Device.Command]]
  ): Behavior[Command] = {

    Behaviors.setup { context =>
      val commandHandler: (State, Command) => Effect[Event, State] = { (state, cmd) =>
        state match {
          case StateDevicesRegistered(devicesRegistered) =>
            cmd match {
              case trackMsg @ RequestTrackDevice(`groupId`, deviceId, replyTo) =>
                  
                if (devicesRegistered(deviceId)) {
                    val entityRef = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                    Effect.none.thenRun(state => replyTo ! DeviceRegistered(deviceId))
                } else {
                  context.log.info("Creating device actor for {}", trackMsg.deviceId)
                  val deviceActor = sharding.entityRefFor(
                      Device.TypeKey,
                      Device.makeEntityId(groupId, deviceId)
                    ) 
                    registerDevice(persistenceId.id, deviceId, deviceActor, replyTo)
                }
                  
               

                /*devicesRegistered.get(deviceId) match {
                  case Some(deviceActor) =>

                    
                    Effect.none.thenRun(state => replyTo ! DeviceRegistered(deviceId))
                  //Behaviors.same
                  case None =>
                    context.log.info("Creating device actor for {}", trackMsg.deviceId)
                    //val deviceActor = context.spawn(Device(groupId, deviceId), s"device-$deviceId")
                    val deviceActor = sharding.entityRefFor(
                      Device.TypeKey,
                      Device.makeEntityId(groupId, deviceId)
                    ) // entityId = groupId||deviceId
                    //context.watchWith(deviceActor, DeviceTerminated(deviceActor, groupId, deviceId)) // does not work for sharding

                    // TODO in Event handler
                    /*val newDeviceIdToActor: Map[String, EntityRef[Device.Command]] =
                      deviceIdToActor + (deviceId -> deviceActor)
                    replyTo ! DeviceRegistered(deviceId, deviceActor) */
                    registerDevice(persistenceId.id, deviceId, deviceActor, replyTo)
                  //getNewBehaviour(groupId, persistenceId, sharding, newDeviceIdToActor)
                } */
              case RequestTrackDevice(gId, _, _) =>
                context.log.warn2(
                  "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
                  gId,
                  groupId
                )
                Effect.none

              case RequestDeviceList(requestId, gId, replyTo) =>
                if (gId == groupId) {
                  Effect.none.thenRun(state =>
                    replyTo ! ReplyDeviceList(requestId, devicesRegistered)
                  )
                } else
                  Effect.none.thenRun(state => ())

              case DeviceTerminated(_, deviceId) =>
                context.log.info("Device actor for {} has been terminated", deviceId)
                unregisterDevice(persistenceId.id, deviceId)

              case RequestAllTemperatures(requestId, gId, replyTo) =>
                if (gId == groupId) {
                  //println("DEVICEGROUP: DEVICES REGISTERED "+devicesRegistered)
                  val deviceId2EntityRefSnapshot =  for {
                    dId <- devicesRegistered
                  } yield { dId -> sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))}
                                    
                  context.spawnAnonymous(
                    DeviceGroupQuery(
                      deviceId2EntityRefSnapshot.toMap, //TODO
                      requestId = requestId,
                      requester = replyTo,
                      FiniteDuration(3, scala.concurrent.duration.SECONDS)
                    )
                  )
                  Effect.none
                } else
                  Effect.unhandled
              //val newDeviceIdToActor: Map[String, EntityRef[Device.Command]] =
              //  deviceIdToActor - deviceId
              //getNewBehaviour(groupId, persistenceId, sharding, newDeviceIdToActor)

            }

        }

      }

      val eventHandler: (State, Event) => State = {
        case (
              StateDevicesRegistered(devicesRegistered),
              EventDeviceRegistered(persistenceId, deviceId )//,deviceEntityRef)
            ) =>
          val newDeviceIdSet: Set[String] =
            devicesRegistered + deviceId //deviceEntityRef)
          println("DEVICESGROUP DEVICES EVENT "+deviceId)
          println(newDeviceIdSet)
          StateDevicesRegistered(newDeviceIdSet)
        case (
              StateDevicesRegistered(devicesRegistered),
              EventDeviceUnRegistered(persistenceId, deviceId)
            ) =>
          val newDeviceIdSet: Set[String] =
            devicesRegistered - deviceId
          println("DEVICESGROUP DEVICES EVENT "+deviceId)
          println(newDeviceIdSet)
          StateDevicesRegistered(newDeviceIdSet) // for serialization

      }

      EventSourcedBehavior[Command, Event, State](
        persistenceId,
        emptyState = StateDevicesRegistered(Set.empty[String]),
        commandHandler,
        eventHandler
      )
    }
  }

  private def registerDevice(
      persistenceId: String,
      deviceId: String,
      deviceEntityRef: EntityRef[Device.Command],
      replyTo: ActorRef[DeviceRegistered]
  ): Effect[Event, State] = {
    Effect.persist(EventDeviceRegistered(persistenceId, deviceId)).thenRun {
      state => replyTo ! DeviceRegistered(deviceId)
    }
  }

  private def unregisterDevice(
      persistenceId: String,
      deviceId: String
  ): Effect[Event, State] = {
    Effect.persist(EventDeviceUnRegistered(persistenceId, deviceId))
  }

  sealed trait State

  final case class StateDevicesRegistered(registeredDevices: Set[String])
      extends State

  sealed trait Event

  final case class EventDeviceRegistered(
      persistenceId: String,
      deviceId: String,
      //deviceEntityRef: EntityRef[Device.Command]
  )                                                                                 extends Event
  final case class EventDeviceUnRegistered(persistenceId: String, deviceId: String) extends Event

  /*private def getNewBehaviour(
      groupId: String,
      persistenceId: PersistenceId,
      sharding: ClusterSharding,
      deviceIdToActor: Map[String, EntityRef[Device.Command]]
  ): Behavior[Command] = {

    Behaviors.setup { context =>
      Behaviors.receiveMessage { msg =>
        msg match {
          case trackMsg @ RequestTrackDevice(`groupId`, deviceId, replyTo) =>
            deviceIdToActor.get(deviceId) match {
              case Some(deviceActor) =>
                replyTo ! DeviceRegistered(deviceId, deviceActor)
                Behaviors.same
              case None =>
                context.log.info("Creating device actor for {}", trackMsg.deviceId)
                //val deviceActor = context.spawn(Device(groupId, deviceId), s"device-$deviceId")
                val deviceActor = sharding.entityRefFor(
                  Device.TypeKey,
                  Device.makeEntityId(groupId, deviceId)
                ) // entityId = groupId||deviceId
                //context.watchWith(deviceActor, DeviceTerminated(deviceActor, groupId, deviceId)) // does not work for sharding
                val newDeviceIdToActor: Map[String, EntityRef[Device.Command]] =
                  deviceIdToActor + (deviceId -> deviceActor)
                replyTo ! DeviceRegistered(deviceId, deviceActor)
                getNewBehaviour(groupId, persistenceId, sharding, newDeviceIdToActor)
            }
          case RequestTrackDevice(gId, _, _) =>
            context.log.warn2(
              "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
              gId,
              groupId
            )
            Behaviors.same

          case RequestDeviceList(requestId, gId, replyTo) =>
            if (gId == groupId) {
              replyTo ! ReplyDeviceList(requestId, deviceIdToActor.keySet)
              Behaviors.same
            } else
              Behaviors.unhandled

          case DeviceTerminated(_, _, deviceId) =>
            context.log.info("Device actor for {} has been terminated", deviceId)
            val newDeviceIdToActor: Map[String, EntityRef[Device.Command]] =
              deviceIdToActor - deviceId
            getNewBehaviour(groupId, persistenceId, sharding, newDeviceIdToActor)

          case RequestAllTemperatures(requestId, gId, replyTo) =>
            if (gId == groupId) {
              context.spawnAnonymous(
                DeviceGroupQuery(
                  deviceIdToActor,
                  requestId = requestId,
                  requester = replyTo,
                  FiniteDuration(3, scala.concurrent.duration.SECONDS)
                )
              )
              Behaviors.same
            } else
              Behaviors.unhandled
        }

      }
    }
  } */

}

/*class DeviceGroup(context: ActorContext[DeviceGroup.Command], groupId: String)
    extends AbstractBehavior[DeviceGroup.Command](context) {
  import DeviceGroup._
  import DeviceManager.{
    DeviceRegistered,
    ReplyDeviceList,
    RequestAllTemperatures,
    RequestDeviceList,
    RequestTrackDevice
  }

  private val sharding = ClusterSharding(context.system)

  private var deviceIdToActor =
    Map.empty[String, EntityRef[Device.Command]] // TODO save entityId for cluster sharding?

  context.log.info("DeviceGroup {} started", groupId)

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case trackMsg @ RequestTrackDevice(`groupId`, deviceId, replyTo) =>
        deviceIdToActor.get(deviceId) match {
          case Some(deviceActor) =>
            replyTo ! DeviceRegistered(deviceId, deviceActor)
          case None =>
            context.log.info("Creating device actor for {}", trackMsg.deviceId)
            //val deviceActor = context.spawn(Device(groupId, deviceId), s"device-$deviceId")
            val deviceActor = sharding.entityRefFor(
              Device.TypeKey,
              Device.makeEntityId(groupId, deviceId)
            ) // entityId = groupId||deviceId
            //context.watchWith(deviceActor, DeviceTerminated(deviceActor, groupId, deviceId)) // does not work for sharding
            deviceIdToActor += deviceId -> deviceActor
            replyTo ! DeviceRegistered(deviceId, deviceActor)
        }
        this

      case RequestTrackDevice(gId, _, _) =>
        context.log.warn2(
          "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
          gId,
          groupId
        )
        this

      case RequestDeviceList(requestId, gId, replyTo) =>
        if (gId == groupId) {
          replyTo ! ReplyDeviceList(requestId, deviceIdToActor.keySet)
          this
        } else
          Behaviors.unhandled

      case DeviceTerminated(_, _, deviceId) =>
        context.log.info("Device actor for {} has been terminated", deviceId)
        deviceIdToActor -= deviceId
        this

      case RequestAllTemperatures(requestId, gId, replyTo) =>
        if (gId == groupId) {
          context.spawnAnonymous(
            DeviceGroupQuery(
              deviceIdToActor,
              requestId = requestId,
              requester = replyTo,
              FiniteDuration(3, scala.concurrent.duration.SECONDS)
            )
          )
          this
        } else
          Behaviors.unhandled

    }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = { case PostStop =>
    context.log.info("DeviceGroup {} stopped", groupId)
    this
  } 
} */

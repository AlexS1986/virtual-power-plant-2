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
  final case class DeviceTerminated(groupId: String, deviceId: String) extends Command

  /**
    * this message requests this actor to add a member
    *
    * @param requestTrackDevice
    */
  final case class WrappedRequestTrackDevice(requestTrackDevice : DeviceManager.RequestTrackDevice) extends Command

  /**
    * this message is sent as a positive response to a request to track this device
    *
    * @param deviceId
    */
  final case class RespondTrackDevice(deviceId: String)

  /**
    * this message requests this actor to return a list of its members
    *
    * @param requestDeviceList
    */
  final case class WrappedRequestDeviceList(requestDeviceList : DeviceManager.RequestDeviceList) extends Command

  /**
    * this message is sent in response to a request to list all members of this actor
    *
    * @param requestId
    * @param ids the set of members
    */
  final case class RespondDeviceList(requestId: Long, ids: Set[String])

  /**
    * this message requests this actor to return the state of all its members
    *
    * @param requestAllData
    */
  final case class WrappedRequestAllData(requestAllData : DeviceManager.RequestAllTemperatures) extends Command

  /**
    * this message is sent in response to a request to return the state of all its members
    *
    * @param requestId
    * @param data a map (deviceId -> read data) of the state of all members
    */
  final case class RespondAllData(requestId: Long, data: Map[String, TemperatureReading])
  
  /**
    * states that this actor can assume
    */
  sealed trait State

  /**
    * state is defined by the current members, i.e. Devices, in this group
    *
    * @param registeredDevices the set of the PersistenceIds of the members of this group
    */
  final case class StateDevicesRegistered(registeredDevices: Set[String]) extends State

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
      def registerDevice(persistenceId: PersistenceId,deviceId: String,replyTo: ActorRef[RespondTrackDevice]): Effect[Event, State] = {
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
            case StateDevicesRegistered(devicesRegistered) =>
              cmd match {
                case WrappedRequestTrackDevice(requestTrackDevice) => requestTrackDevice match {
                    case trackMsg @ DeviceManager.RequestTrackDevice(`groupId`, deviceId, replyTo) =>
                      if (devicesRegistered(deviceId)) {
                          val entityRef = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
                          Effect.none.thenRun(state => replyTo ! RespondTrackDevice(deviceId)) //DeviceRegistered(deviceId))
                      } else {
                        context.log.info("Creating device actor for {}", trackMsg.deviceId)
                        val deviceActor = sharding.entityRefFor(
                            Device.TypeKey,
                            Device.makeEntityId(groupId, deviceId)
                          ) 
                          registerDevice(persistenceId, deviceId, replyTo)
                      }
                    case DeviceManager.RequestTrackDevice(gId, _, _) =>
                      context.log.warn2(
                        "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
                        gId,
                        groupId
                      )
                      Effect.none
                  }
                case WrappedRequestDeviceList(requestDeviceList) => requestDeviceList match {
                  case DeviceManager.RequestDeviceList(requestId, gId, replyTo) =>
                    if (gId == groupId) {
                      Effect.none.thenRun(state =>
                        replyTo ! RespondDeviceList(requestId, devicesRegistered)
                      )
                    } else {
                      Effect.none.thenRun(state => ())
                    }   
                } 
                case DeviceTerminated(_, deviceId) =>
                  context.log.info("Device actor for {} has been terminated", deviceId)
                  unregisterDevice(persistenceId, deviceId)
                case WrappedRequestAllData(requestAllData) => requestAllData match {
                  case DeviceManager.RequestAllTemperatures(requestId, gId, replyTo) =>
                    if (gId == groupId) {
                      val deviceId2EntityRefSnapshot =  for {
                        dId <- devicesRegistered
                      } yield { dId -> sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, dId))}
                                        
                      context.spawnAnonymous(
                        DeviceGroupQuery(deviceId2EntityRefSnapshot.toMap, requestId = requestId,requester = replyTo, FiniteDuration(3, scala.concurrent.duration.SECONDS)))
                      Effect.none
                    } else
                      Effect.unhandled
                }   
              }
          }
        }

        /**
        * processes persisted Events and may change the current State
        */
        val eventHandler: (State, Event) => State = {
          case (StateDevicesRegistered(devicesRegistered), EventDeviceRegistered(persistenceId, deviceId )) =>
            val newDeviceIdSet: Set[String] =
              devicesRegistered + deviceId 
            StateDevicesRegistered(newDeviceIdSet)
          case (StateDevicesRegistered(devicesRegistered),EventDeviceUnRegistered(persistenceId, deviceId)) =>
            val newDeviceIdSet: Set[String] =
              devicesRegistered - deviceId
            StateDevicesRegistered(newDeviceIdSet) 
        }

        // create the behavior
        EventSourcedBehavior[Command, Event, State](
          persistenceId,
          emptyState = StateDevicesRegistered(Set.empty[String]),
          commandHandler,
          eventHandler)
      }
    }
    getNewBehaviour()
  }
}

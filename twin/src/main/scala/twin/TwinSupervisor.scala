package twin

import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
//import akka.actor.typed.ActorRef
//import scala.concurrent.duration.FiniteDuration
//import akka.actor.typed.scaladsl.TimerScheduler

//import scala.collection.JavaConverters._
//import _root_.com.typesafe.config.ConfigFactory
//import akka.actor.AddressFromURIString
//import _root_.com.typesafe.config.Config

// akka cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.{actor => classic}
import akka.cluster.ClusterEvent
import akka.cluster.typed.{Cluster, Subscribe}

import twin.repository.ScalikeJdbcSetup
import twin.repository.DeviceRepositoryImpl
import twin.projections.DeviceProjection
import twin.network.TwinHttpServer

// the user guardian of the twin Microservice
object TwinSupervisor {

  /**
    * the messages that this actor can process
    */
  trait Command 

  def apply(httpPort: Int): Behavior[TwinSupervisor.Command] = {
    Behaviors.setup[TwinSupervisor.Command](context => {

      val cluster = Cluster(context.system)
      context.log.info("Started [" + context.system + "], cluster.selfAddress = " + cluster.selfMember.address + ")")
      
      // Create an actor that handles cluster domain events
      val listener = context.spawn(
        Behaviors.receive[ClusterEvent.MemberEvent]((ctx, event) => {
          ctx.log.info("MemberEvent: {}", event)
          Behaviors.same
        }),
        "listener"
      )
      
      //subscribe to cluster events
      Cluster(context.system).subscriptions ! Subscribe(listener, classOf[ClusterEvent.MemberEvent])

      // Akka Management hosts the HTTP routes used by bootstrap
      AkkaManagement(context.system).start() // also for readiness probes etc?

      // Starting the bootstrap process needs to be done explicitly
      ClusterBootstrap(context.system).start()

      // initialize cluster sharding for Devices and DeviceGroups
      Device.initSharding(context.system)
      DeviceGroup.initSharding(context.system)

      // akka projection requires access to readside database
      ScalikeJdbcSetup.init(context.system) 
    
      // akka projection and access to database
      val deviceTemperatureRepository = new DeviceRepositoryImpl() // connection to database
      
      // initioalize projection
      DeviceProjection.init(context.system,deviceTemperatureRepository)

      // create user guardian
      new IotSupervisor(context, httpPort)
    })
  }

}

class IotSupervisor(context: ActorContext[TwinSupervisor.Command], httpPort: Int)
    extends AbstractBehavior[TwinSupervisor.Command](context) {
  context.log.info("Twin Microservice started")

  // spawn DeviceManager actors that provice access to application
  val deviceManagers = Seq(context.spawn[DeviceManager.Command](DeviceManager(), "DeviceManager" + "A"),
                           context.spawn[DeviceManager.Command](DeviceManager(), "DeviceManager" + "B"))
  
  // spawn http-server
  val routes = new twin.network.TwinRoutes(context.system, deviceManagers)
  TwinHttpServer.start(routes.devices, httpPort, context.system)

  override def onMessage(msg: TwinSupervisor.Command): Behavior[TwinSupervisor.Command] = {
    // No need to handle any messages since supervisor just spawns other actors and server
    Behaviors.unhandled
  }

  override def onSignal: PartialFunction[Signal, Behavior[TwinSupervisor.Command]] = {
    case PostStop =>
      context.log.info("Twin Microservice stopped")
      this
  }
}

import akka.actor.typed.ActorSystem

object TwinApp {

  def main(args: Array[String]): Unit = {
    val httpPort = 8080
    ActorSystem[TwinSupervisor.Command](TwinSupervisor(httpPort), "twin")
  }
}

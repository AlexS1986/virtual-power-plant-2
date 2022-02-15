package twin

import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import scala.concurrent.duration.FiniteDuration
import akka.actor.typed.scaladsl.TimerScheduler

import scala.collection.JavaConverters._
import _root_.com.typesafe.config.ConfigFactory
import akka.actor.AddressFromURIString
import _root_.com.typesafe.config.Config

// akka cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.{actor => classic}
import akka.cluster.ClusterEvent
import akka.cluster.typed.{Cluster, Subscribe}
//import _root_.com.example.repository.ScalikeJdbcSetup
//import _root_.com.example.repository.DeviceTemperatureRepositoryImpl

import twin.repository.ScalikeJdbcSetup
import twin.repository.DeviceRepositoryImpl
import twin.projections.DeviceProjection

import twin.network.DeviceHttpServer

/*object IotSupervisor {
  def apply(httpPort: Int) : Behavior[Nothing] = Behaviors.setup[Nothing] {
    context =>
    val deviceManager = context.spawn(DeviceManager(),"deviceManager")
    val routes = new DeviceRoutes(context.system,deviceManager)
    DeviceHttpServer.start(routes.devices,httpPort,context.system)
    Behaviors.empty
  }
} */

object IotSupervisor {

  trait Command //extends DeviceManagerClient

  def apply(httpPort: Int): Behavior[IotSupervisor.Command] = {
    Behaviors.setup[IotSupervisor.Command](context => {

      // akka cluster
      // logging cluster events
      val cluster = Cluster(context.system)
      context.log.info(
        "Started [" + context.system + "], cluster.selfAddress = " + cluster.selfMember.address + ")"
      )
      // Create an actor that handles cluster domain events
      val listener = context.spawn(
        Behaviors.receive[ClusterEvent.MemberEvent]((ctx, event) => {
          ctx.log.info("MemberEvent: {}", event)
          Behaviors.same
        }),
        "listener"
      )
      Cluster(context.system).subscriptions ! Subscribe(listener, classOf[ClusterEvent.MemberEvent])

      ScalikeJdbcSetup.init(context.system) // for query side cqrs. is accessed via ScalikeJdbc

      // Akka Management hosts the HTTP routes used by bootstrap
      AkkaManagement(context.system).start() // also for readiness probes etc?

      // Starting the bootstrap process needs to be done explicitly
      ClusterBootstrap(context.system).start()

      // akka cluster

      // start cluster sharding
      Device.initSharding(context.system)
      DeviceGroup.initSharding(context.system)
      //

      // akka projection and access to database
      val deviceTemperatureRepository = new DeviceRepositoryImpl() // connection to database
      
      DeviceProjection.init(context.system,deviceTemperatureRepository)

      new IotSupervisor(context, httpPort)
    })
  }

}

class IotSupervisor(context: ActorContext[IotSupervisor.Command], httpPort: Int)
    extends AbstractBehavior[IotSupervisor.Command](context) {
  context.log.info("IoT Application started")

  val deviceManagers = Seq(context.spawn[DeviceManager.Command](DeviceManager(), "DeviceManager" + "A"),
                           context.spawn[DeviceManager.Command](DeviceManager(), "DeviceManager" + "B"))
  val deviceManager: ActorRef[DeviceManager.Command] =
    context.spawn[DeviceManager.Command](DeviceManager(), "DeviceManager")

  /*val networkActor: ActorRef[NetworkActor.Command] =
    context.spawn[NetworkActor.Command](NetworkActor(httpPort, deviceManager), "NetworkActor") */

  val routes = new twin.network.DeviceRoutes(context.system, deviceManagers,deviceManager) //networkActor, deviceManager)
  DeviceHttpServer.start(routes.devices, httpPort, context.system)

  override def onMessage(msg: IotSupervisor.Command): Behavior[IotSupervisor.Command] = {
    // No need to handle any messages
    Behaviors.unhandled

  }

  override def onSignal: PartialFunction[Signal, Behavior[IotSupervisor.Command]] = {
    case PostStop =>
      context.log.info("IoT Application stopped")
      this
  }
}

import akka.actor.typed.ActorSystem

object IotApp {

  def main(args: Array[String]): Unit = {
    val httpPort = 8080
    //val config: Config = ConfigFactory.parseString(""" akka.actor.provider=cluster""")
    //ActorSystem[IotSupervisor.Command](IotSupervisor(httpPort), "iot-system")
    ActorSystem[IotSupervisor.Command](IotSupervisor(httpPort), "twin")

    /*val seedNodePorts = ConfigFactory.load().getStringList("akka.cluster.seed-nodes")
      .asScala
      .flatMap { case AddressFromURIString(s) => s.port } */

    /*val ports = args.headOption match {
      case Some(port) => Seq(port.toInt)
      case None       => seedNodePorts ++ Seq(0)
    }  */

    //ports.foreach{ port =>
    //  val httpPort =
    //    if (port > 0) 10000 + port
    //    else 0

    //val config = configWithPort(port)
    // Create ActorSystem and top level supervisor
    //ActorSystem[Nothing](IotSupervisor(httpPort), "iot-system") //,config)
    //}

  }

  /*private def configWithPort(port: Int): Config =
    ConfigFactory.parseString(s"""
       akka.remote.artery.canonical.port = $port
        """).withFallback(ConfigFactory.load()) */

}

package com.example.projections

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.projection.{ProjectionBehavior, ProjectionId}
import com.example.repository.DeviceTemperatureRepository
import com.example.Device
import com.example.repository.ScalikeJdbcSession
import akka.actor.typed.scaladsl.ActorContext

object DeviceTemperatureProjection {

  def init(system: ActorSystem[_], repository: DeviceTemperatureRepository): Unit = {
    
    ShardedDaemonProcess(system).init(
      name = "DeviceTemperatureProjection",
      Device.tags.size,//1,//Device.tags.size,
      index => ProjectionBehavior(createProjectionFor(system, repository, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
   // context.log.info("ALERT: SHARDING PROCESS INIT.")
  }

  private def createProjectionFor(
      system: ActorSystem[_],
      repository: DeviceTemperatureRepository,
      index: Int,
     // context: ActorContext[_] // DEBUG
  ): ExactlyOnceProjection[Offset, EventEnvelope[Device.Event]] = {
    val tag = Device.tags(index) //"device-temperature-projection-1"//Device.tags(index)

    system.log.info("TAGPROJECTION "+tag+" TAGPROJECTION")

    val sourceProvider: SourceProvider[Offset, EventEnvelope[Device.Event]] =
      EventSourcedProvider.eventsByTag[Device.Event](
        system = system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = tag
      )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId("DeviceTemperatureProjection", tag),
      sourceProvider,
      handler = () => new DeviceTemperatureProjectionHandler(tag, system, repository),
      sessionFactory = () => new ScalikeJdbcSession()
    )(system)
  }

}

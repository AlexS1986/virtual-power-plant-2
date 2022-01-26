package com.example.repository

import scalikejdbc._
import java.time.LocalDateTime
//import java.time.format.DateTimeFormatter

trait DeviceTemperatureRepository {
    def update(session: ScalikeJdbcSession, deviceId: String, temperature:Double) : Unit
    def delete(session: ScalikeJdbcSession, deviceId:String) : Unit
    def getTemperature(session: ScalikeJdbcSession, deviceId: String) : Option[Double]
    def getAllDevicesAndTemperatures(session:ScalikeJdbcSession) : List[(String,Double)]

    //def recordEnergyDeposit(session: ScalikeJdbcSession, vppId: String, deviceId: String,timestamp: LocalDateTime, energyDeposit: Double) : Unit
    def queryEnergyDeposits(session: ScalikeJdbcSession, vppId: String, before: LocalDateTime, after: LocalDateTime) : Option[Double]

    def deleteEnergyDeposits(session: ScalikeJdbcSession, before : LocalDateTime) :Unit
}

class DeviceTemperatureRepositoryImpl() extends DeviceTemperatureRepository {

  override def deleteEnergyDeposits(session: ScalikeJdbcSession, before : LocalDateTime): Unit =  {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx {
        implicit dbSession =>
          deleteEnergyDeposits(before)
      }
    } else {
      session.db.localTx{
        implicit dbSession =>
          deleteEnergyDeposits(before)
      }
    }
  }

private def deleteEnergyDeposits(before : LocalDateTime)(implicit dbSession:DBSession) : Unit = {
  
        //sql"SELECT device_id FROM device_temperature".map(_.string("device_id")).list().apply()
        sql"""DELETE FROM energy_deposit WHERE time_stamp < $before""".execute().apply()    
}

  override def queryEnergyDeposits(session: ScalikeJdbcSession, vppId: String, before: LocalDateTime, after: LocalDateTime): Option[Double] = {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx { implicit dbSession =>
        queryDbForEnergyDeposits(vppId,before,after)
      }
    } else {
      session.db.readOnly { implicit dbSession =>
         queryDbForEnergyDeposits(vppId,before,after)
      }
    }
  }

  private def queryDbForEnergyDeposits(vppId: String, before: LocalDateTime, after: LocalDateTime)(implicit dbSession: DBSession) : Option[Double] = {
    //val afterAsString = after.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    // must be compared directly as LocalDateTime and not as String
    /*sql""" SELECT SUM(energy_deposited) FROM energy_deposit WHERE vpp_id = $vppId AND time_stamp > $after   
       """ */
    sql""" SELECT SUM(energy_deposited) FROM energy_deposit WHERE vpp_id = $vppId AND time_stamp BETWEEN $after AND $before   
       """
       .map(wrappedResultSet => wrappedResultSet.doubleOpt("sum"))
       .single
       .apply.map {
         case Some(energyDeposited) => energyDeposited
         case None => 0
       }
       /*.single
       .apply */
       
       //.map (_.double("sum"))
       //.toOption()
       //.apply()
  }


  /*override def recordEnergyDeposit(session: ScalikeJdbcSession, vppId: String, deviceId: String, timestamp: LocalDateTime, energyDeposit: Double): Unit = {
    session.db.withinTx { implicit dbSession =>
      //val timestampAsString = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
      sql"""
          INSERT INTO energy_deposit VALUES($vppId,$deviceId,$timestamp,$energyDeposit)
          ON CONFLICT (vpp_id,device_id,time_stamp) DO UPDATE SET energy_deposited = $energyDeposit
         """.executeUpdate().apply()
    }
    
  }*/

  override def delete(session: ScalikeJdbcSession, deviceId: String): Unit = {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx {
        implicit dbSession =>
          deleteDevice(deviceId)
      }
    } else {
      session.db.localTx{
        implicit dbSession =>
          deleteDevice(deviceId)
      }
    }
    /*session.db.withinTx{
      implicit dbSession =>
        sql"""DELETE FROM device_temperature WHERE device_id = $deviceId""".execute().apply()
    } */
  }

  private def deleteDevice(deviceId : String)(implicit dbSession:DBSession) : Unit = {
  
        //sql"SELECT device_id FROM device_temperature".map(_.string("device_id")).list().apply()
        sql"""DELETE FROM device_temperature WHERE device_id = $deviceId""".execute().apply()
    
  }

  


  override def update(
      session: ScalikeJdbcSession,
      deviceId: String,
      temperature: Double): Unit = {
    session.db.withinTx { implicit dbSession =>
      // This uses the PostgreSQL `ON CONFLICT` feature
      // Alternatively, this can be implemented by first issuing the `UPDATE`
      // and checking for the updated rows count. If no rows got updated issue
      // the `INSERT` instead.
      sql"""
           INSERT INTO device_temperature (device_id, temperature) VALUES ($deviceId, $temperature)
           ON CONFLICT (device_id) DO UPDATE SET temperature = $temperature
         """.executeUpdate().apply()
    }
  }

override def getTemperature(
      session: ScalikeJdbcSession,
      deviceId: String): Option[Double] = {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx { implicit dbSession =>
        select(deviceId)
      }
    } else {
      session.db.readOnly { implicit dbSession =>
        select(deviceId)
      }
    }
  }

  private def select(deviceId: String)(implicit dbSession: DBSession) : Option[Double] = {
    sql"SELECT temperature FROM device_temperature WHERE device_id = $deviceId"
      .map(_.double("temperature"))
      .toOption()
      .apply()
  }

  override def getAllDevicesAndTemperatures(session: ScalikeJdbcSession): List[(String,Double)] =  {
    
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx { implicit dbSession =>
        selectAllDevicesAndTemperatures()
      }
    } else {
      session.db.readOnly { implicit dbSession =>
        selectAllDevicesAndTemperatures()
      }
    }
  }

  private def selectAllDevicesAndTemperatures()(implicit dbSession:DBSession) : List[(String,Double)] = {
  
        //sql"SELECT device_id FROM device_temperature".map(_.string("device_id")).list().apply()
        sql"SELECT device_id,temperature FROM device_temperature".map(rs => (rs.string("device_id"), rs.double("temperature"))).list().apply()
    
  }

}
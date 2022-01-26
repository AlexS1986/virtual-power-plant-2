package com.example.repository

import scalikejdbc._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

trait DeviceTemperatureRepository {
    def update(session: ScalikeJdbcSession, deviceId: String, temperature:Double) : Unit
    def getTemperature(session: ScalikeJdbcSession, deviceId: String) : Option[Double] // TODO can be removed on write side

    def recordEnergyDeposit(session: ScalikeJdbcSession, vppId: String, deviceId: String, energyDeposit: Double, timestamp: LocalDateTime) : Unit
    /*def recordEnergyDeposit(session: ScalikeJdbcSession, vppId: String, deviceId: String,timestamp: LocalDateTime, energyDeposit: Double) : Unit
    def queryEnergyDeposits(session: ScalikeJdbcSession, vppId:String, past : LocalDateTime) : Option[Double] */
}

class DeviceTemperatureRepositoryImpl() extends DeviceTemperatureRepository {

  /*override def queryEnergyDeposits(session: ScalikeJdbcSession, vppId: String, past: LocalDateTime): Option[Double] = {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx { implicit dbSession =>
        queryDbForEnergyDeposits(vppId,past)
      }
    } else {
      session.db.readOnly { implicit dbSession =>
         queryDbForEnergyDeposits(vppId,past)
      }
    }
  } */

override def recordEnergyDeposit(session: ScalikeJdbcSession, vppId: String, deviceId: String,  energyDeposit: Double, timestamp: LocalDateTime): Unit = {
    println("RECORD ENERGY HIT!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
    session.db.withinTx { implicit dbSession =>
      //val timestampAsString = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
      sql"""
          INSERT INTO energy_deposit VALUES($vppId,$deviceId,$timestamp,$energyDeposit)
          ON CONFLICT (vpp_id,device_id,time_stamp) DO UPDATE SET energy_deposited = $energyDeposit
         """.executeUpdate().apply()
    }
    
  }

  /*
  override def recordEnergyDeposit(session: ScalikeJdbcSession, vppId: String, deviceId: String, timestamp: LocalDateTime, energyDeposit: Double): Unit = {
    session.db.withinTx { implicit dbSession =>
      val timestampAsString = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
      sql"""
          INSERT INTO energy_deposit VALUES($vppId,$deviceId,$timestampAsString,$energyDeposit)
          ON CONFLICT (vpp_id,device_id,time_stamp) DO UPDATE SET energy_deposited = $energyDeposit
         """.executeUpdate().apply()
    }
    
  }
  */

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


  /*private def queryDbForEnergyDeposits(vppId: String, after: LocalDateTime)(implicit dbSession: DBSession) : Option[Double] = {
    val afterAsString = after.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    sql""" SELECT SUM(energy_deposited) FROM energy_deposit WHERE vpp_id = $vppId AND time_stamp > $afterAsString  
       """
       .map (_.double("sum"))
       .toOption()
       .apply()
  } */

  private def select(deviceId: String)(implicit dbSession: DBSession) : Option[Double] = {
    sql"SELECT temperature FROM device_temperature WHERE device_id = $deviceId"
      .map(_.double("temperature"))
      .toOption()
      .apply()
  }

}
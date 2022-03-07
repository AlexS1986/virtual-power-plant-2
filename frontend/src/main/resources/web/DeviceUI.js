"use strict"

class DeviceUI {
    constructor(deviceId, groupId) {
        this.deviceId = deviceId;
        this.groupId = groupId;
        this.simulationState = "active";
        this.temperature = 0 // TODO rename as as charge status default start value
        this.currentHost = ""
        this.lastTenDeliveredEnergyReadings = []
        console.log("Device created! " + this.createDeviceIndentifier())
    }

    getSimulationState(){
        return this.simulationState
    }

    stop() {
        this.sendStopNotificationToServer()
        this.simulationState = "passive"
    }

    start() {
        this.sendStartNotificationToServer()
    }

    setTemperature (temperature) {
        this.temperature = temperature
    }

    setCurrentHost (currentHost) {
        this.currentHost= currentHost
    }

    setLastTenDeliveredEnergyReadings(lastTenDeliveredEnergyReadings) {
        this.lastTenDeliveredEnergyReadings = lastTenDeliveredEnergyReadings
    }

    getLastTenDeliveredEnergyReadings() {
        return this.lastTenDeliveredEnergyReadings
    }

    static parseDeviceIdentifier(deviceIdentifier, defaultGroupName = "default") {
        var groupIdDeviceId = [defaultGroupName, deviceIdentifier];
        if (deviceIdentifier.indexOf("|") > -1 && deviceIdentifier.indexOf("&&&") > -1) {
            groupIdDeviceId = deviceIdentifier.split("|")[1].split("&&&")
        }

        return {
            "groupId": groupIdDeviceId[0],
            "deviceId": groupIdDeviceId[1]
        }
    }

    createDeviceIndentifier() {
        return DeviceUI.createDeviceIndentifier(this.groupId,this.deviceId)
    }

    static createDeviceIndentifier(groupId,deviceId) {
        return "Device|"+groupId+"&&&"+deviceId
    } 

    sendStartNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"deviceId": this.deviceId,"groupId": this.groupId })
        Util.sendRequestToServer("simulator/"+this.groupId+"/"+this.deviceId + "/start","POST",data,headers)
    }

    sendStopNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        Util.sendRequestToServer("vpp/device/"+this.groupId+"/"+this.deviceId,"DELETE",null,headers)
    }


    

    


}
"use strict"

class DeviceSimulator {
    // needs to have a capacity, a current battery state, an power output, a maximum power output, a function to send data to server, a function to set desired output

    constructor(deviceId, groupId) {
        this.deviceId = deviceId;
        this.groupId = groupId;
        this.simulationState = "active";
        this.temperature = 0 // default start value
        this.currentHost = ""
        console.log("DeviceSimulator created! " + this.createDeviceIndentifier())
    }

    getSimulationState(){
        return this.simulationState
    }

    stopSimulation() {
        this.sendStopNotificationToServer()
        this.simulationState = "passive"
        //this.sendDeleteNotificationToServer()
        /*setTimeout(function(deviceSimulatorToStop) { // give all post Requests time to be processed then remove 
            deviceSimulatorToStop.sendDeleteNotificationToServer()
        },10000,this)*/
    }

    setTemperature (temperature) {
        this.temperature = temperature
    }

    setCurrentHost (currentHost) {
        this.currentHost= currentHost
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
        return DeviceSimulator.createDeviceIndentifier(this.groupId,this.deviceId)
    }

    static createDeviceIndentifier(groupId,deviceId) {
        return "Device|"+groupId+"&&&"+deviceId
    }

    /*sendDeleteNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"deviceId": this.deviceId,"groupId": this.groupId })
        Util.sendRequestToServer("/delete","DELETE",data,headers)
    }*/
    

    sendStartNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"deviceId": this.deviceId,"groupId": this.groupId })
        Util.sendRequestToServer("simulator/"+this.groupId+"/"+this.deviceId + "/start","POST",data,headers)
    }

    sendStopNotificationToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"deviceId": this.deviceId,"groupId": this.groupId })
        Util.sendRequestToServer("vpp/device/"+this.groupId+"/"+this.deviceId,"DELETE",data,headers)
        //Util.sendRequestToServer("/stop","POST",data,headers)
    }


    /*var vppId = "default"
        var after = "test"//"2001-10-19 10:23:54"
        var headers2 =  {"Content-Type" : "application/json"}
        var data2 = JSON.stringify({"vppId": vppId,"after": after })
        data2 = '"{"vppId":"default","after":"test"}"' */
    /*sendRandomMessageToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"vppId": "default","after": "2001-10-19 10:23:54" })
        Util.sendRequestToServer("/energy","POST",data,headers)
    } */

    


}
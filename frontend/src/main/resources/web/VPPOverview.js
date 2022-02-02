"use strict"
const defaultGroupName = "default"

class VPPOverview {
    constructor(paper,deviceSimulatorsToRender,htmlToDisplayDeviceList) {
        this.paper = paper
        this.deviceSimulatorsToRender = deviceSimulatorsToRender // associative array
        this.htmlToDisplayDeviceList = htmlToDisplayDeviceList
        this.currentIdAppendix = 0
    }

    addDeviceSimulator() {
        var tmp = this.createUniqueId("device",defaultGroupName)
        var deviceId = tmp[0]
        var groupId = tmp[1]
        var uniqueId = tmp[2]
        this.deviceSimulatorsToRender[uniqueId] = new DeviceSimulator(deviceId,groupId)
        this.deviceSimulatorsToRender[uniqueId].sendStartNotificationToServer()
    }

    removeDeviceSimulator() {
        var keys = Object.keys(this.deviceSimulatorsToRender)
        for (var i = 0; i< keys.length;i++) {
            var keyOfDeviceSimulatorToStop = keys[i]
            var deviceSimulatorToStop = this.deviceSimulatorsToRender[keyOfDeviceSimulatorToStop]
            if (deviceSimulatorToStop.getSimulationState() == "active") {
                deviceSimulatorToStop.stopSimulation()
                /*setTimeout(function(deviceSimulatorToStop) { // give all post Requests time to be processed then remove 
                    deviceSimulatorToStop.sendDeleteNotificationToServer()
                },10000,deviceSimulatorToStop)*/
                break;
            }
        }           
    }

    createUniqueId(baseDeviceName,groupId) {
        var uniqueId = DeviceSimulator.createDeviceIndentifier(groupId,baseDeviceName+this.currentIdAppendix)
        if (uniqueId in this.deviceSimulatorsToRender) {
            this.currentIdAppendix=this.currentIdAppendix+1;
            uniqueId = this.createUniqueId(baseDeviceName,groupId)
            return uniqueId
        } else {
            return [baseDeviceName+this.currentIdAppendix,groupId, uniqueId]
        }
    }

    postUpdateOfDeviceDataFromServer(deviceDataFromServer, groupId) {
        var keys = Object.keys(deviceDataFromServer)
        //var groupId = "default" // TODO get also in request?
        for (let i = 0; i<keys.length; i++) {
            var deviceId = keys[i]
            var device = deviceDataFromServer[deviceId]
           
            var uniqueId = DeviceSimulator.createDeviceIndentifier(groupId,deviceId)
            if (uniqueId in this.deviceSimulatorsToRender) {
                if(this.deviceSimulatorsToRender[uniqueId].getSimulationState()=="passive") { // do"passive" as a field, is passive but still sends data, maybe because message was not delivered correctly
                    this.deviceSimulatorsToRender[uniqueId].stopSimulation()
                }
            } else {
                this.deviceSimulatorsToRender[uniqueId] = new DeviceSimulator(deviceId,groupId)
                // send start notification is not necessary since actor state is rendered and not DB
            }

            var dataDescription = device.description
            if(dataDescription == "temperature") {// data is availables
                var dataValue = device.value.value
                
                this.deviceSimulatorsToRender[uniqueId].setTemperature(dataValue)

                var currentHost = device.value.currentHost
                this.deviceSimulatorsToRender[uniqueId].setCurrentHost(currentHost)
            }
           
        }

        // passivate those DeviceSimulators that are stopped at Server
        for (const key of Object.keys(this.deviceSimulatorsToRender)) {
            var parsedDeviceIdentifier = (DeviceSimulator.parseDeviceIdentifier(key))
            if (!(deviceDataFromServer.hasOwnProperty(parsedDeviceIdentifier.deviceId)) && (parsedDeviceIdentifier.groupId == groupId)) { 
                this.deviceSimulatorsToRender[key].simulationState = "passive"
            }
        }



        
        /*for (let i = 0; i < deviceDataFromServer.length; i++) {
            var deviceIdTemperature = deviceDataFromServer[i]
            var uniqueId = deviceIdTemperature.deviceId
            if (uniqueId in this.deviceSimulatorsToRender) {
                this.deviceSimulatorsToRender[uniqueId].setTemperature( deviceIdTemperature.temperature)
            } else { // device in DB but not in simulator
                let deviceId = DeviceSimulator.parseDeviceIdentifier(uniqueId,defaultGroupName).deviceId
                let groupId = DeviceSimulator.parseDeviceIdentifier(uniqueId,defaultGroupName).groupId
                this.deviceSimulatorsToRender[uniqueId] = new DeviceSimulator(deviceId, groupId)
                this.deviceSimulatorsToRender[uniqueId].sendStartNotificationToServer()
            }
        } */
    }

    /*postUpdateOfDeviceDataFromServerReadside(deviceDataFromServer) {
        for (let i = 0; i < deviceDataFromServer.length; i++) {
            var deviceIdTemperature = deviceDataFromServer[i]
            var uniqueId = deviceIdTemperature.deviceId
            if (uniqueId in this.deviceSimulatorsToRender) {
                this.deviceSimulatorsToRender[uniqueId].setTemperature( deviceIdTemperature.temperature)
            } else { // device in DB but not in simulator
                let deviceId = DeviceSimulator.parseDeviceIdentifier(uniqueId,defaultGroupName).deviceId
                let groupId = DeviceSimulator.parseDeviceIdentifier(uniqueId,defaultGroupName).groupId
                this.deviceSimulatorsToRender[uniqueId] = new DeviceSimulator(deviceId, groupId)
                this.deviceSimulatorsToRender[uniqueId].sendStartNotificationToServer()
            }
        }
    } */

    plot() {

        //paper.project.activeLayer.removeChildren()
        //paper.view.draw()

        //this.plotDeviceDisplay()
        this.plotDeviceList()

        
    }

    plotDeviceDisplay() {
        this.paper.project.activeLayer.removeChildren()
        this.paper.view.draw()

        var keys = Object.keys(this.deviceSimulatorsToRender)

        var c;
        var i = 0
        for (var x = 25; x < 400; x += 50) {
            if (i >= keys.length) break;
            for (var y = 25; y < 400; y += 50) {
                if (i >= keys.length) break;
                var deviceSimulatorToRender = this.deviceSimulatorsToRender[keys[i]] // TODO can be null?
                if (deviceSimulatorToRender.getSimulationState() == "active") {
                    c = Shape.Circle(x, y, deviceSimulatorToRender.temperature / 60 * 20);
                    c.fillColor = 'green';
                }
                i = i + 1
            }
        }
    }

    plotDeviceList() {
        /*var keys = Object.keys(this.deviceSimulatorsToRender)
        var deviceListAsHtml = "";
        for (var i = 0; i < keys.length; i++) {
            var deviceSimulatorToRender = this.deviceSimulatorsToRender[keys[i]]
            if (deviceSimulatorToRender.getSimulationState() == "active")  {
                deviceListAsHtml += deviceSimulatorToRender.deviceId + " " + deviceSimulatorToRender.temperature + '<br>'
            }   
        }
        this.htmlToDisplayDeviceList.innerHTML = deviceListAsHtml; */
        
        this.tableCreate();
    
    }

    tableCreate() {

        this.htmlToDisplayDeviceList.innerHTML = "";
        const tbl = document.createElement('table');
        this.htmlToDisplayDeviceList.appendChild(tbl);
        tbl.setAttribute('id','deviceTable')
          
        tbl.style.width = '100px';
        tbl.style.border = '1px solid black';
      
        const tr = tbl.insertRow();
        var myHeaders = ["id","chargeStatus", "capacity", "host","", ""]
        for (let i = 0; i< myHeaders.length; i++) {
            const th = document.createElement('th')
            th.appendChild(document.createTextNode(myHeaders[i]));
            tr.appendChild(th)
                //th.style.border = '1px solid black';
        }
        //if(document.getElementById('deviceTable') == null) {
            
        //}
        
        //const tbl = document.getElementById('deviceTable')
        var keys = Object.keys(this.deviceSimulatorsToRender)
        for (let i = 0; i < keys.length; i++) {
            var deviceSimulatorToRender = this.deviceSimulatorsToRender[keys[i]]
            //deviceSimulatorToRender.sendStopNotificationToServer() // TODO remove
            const tr = tbl.insertRow();
            if (deviceSimulatorToRender.getSimulationState() == "active")  {
                const tdId = tr.insertCell();
                tdId.appendChild(document.createTextNode(deviceSimulatorToRender.deviceId))
                //tdId.style.border = '1px solid black';

                const tdChargeStatus = tr.insertCell();
                const divChargeStatus = document.createElement('div')
                divChargeStatus.setAttribute('id',deviceSimulatorToRender.deviceId)
                divChargeStatus.setAttribute('class',"bh-batteryWidget")
                //var myDiv = $('#'+deviceSimulatorToRender.deviceId)
                
                //myDiv.innerHTML = deviceSimulatorToRender.temperature
                const batteryWidget = new BatteryWidget(divChargeStatus)
                batteryWidget.setBatteryValue(deviceSimulatorToRender.temperature*2)
                //divChargeStatus.appendChild(document.createTextNode(deviceSimulatorToRender.temperature))
                tdChargeStatus.appendChild(divChargeStatus)
                tr.appendChild(tdChargeStatus)

                const tdCapacity = tr.insertCell();
                tdCapacity.appendChild(document.createTextNode("100"))


                const tdHost = tr.insertCell();
                tdHost.appendChild(document.createTextNode(deviceSimulatorToRender.currentHost))
                //tdId.style.border = '1px solid black';


                const tdDetails = tr.insertCell();
                const detailsLink = document.createElement("a");
                detailsLink.innerHTML = "details"
                detailsLink.setAttribute("href","vpp/device/"+deviceSimulatorToRender.groupId+"/"+deviceSimulatorToRender.deviceId)
                tdDetails.appendChild(detailsLink)

                const tdStop = tr.insertCell();
                const stopButton = document.createElement("button");
                stopButton.innerHTML = "stop"
                
                stopButton.device = deviceSimulatorToRender
                stopButton.onclick = function(event) {
                    event.currentTarget.device.stopSimulation()
                }
                tdStop.appendChild(stopButton)

                //var test = $('#'+deviceSimulatorToRender.deviceId).find('.batteryContainer')
                //$('#'+deviceSimulatorToRender.deviceId).find('.batteryContainer').hide().show(0);
            } else { // if passive try to remove

                //deviceSimulatorToRender.stopSimulation() 
            }  
        }

        //this.htmlToDisplayDeviceList.style.display = 'none'
        //this.htmlToDisplayDeviceList.style.display = 'block'


       /* for (let i = 0; i < 3; i++) {
          const tr = tbl.insertRow();
          for (let j = 0; j < 2; j++) {
            if (i === 2 && j === 1) {
              break;
            } else {
              const td = tr.insertCell();
              td.appendChild(document.createTextNode(`Cell I${i}/J${j}`));
              td.style.border = '1px solid black';
              if (i === 1 && j === 1) {
                td.setAttribute('rowSpan', '2');
              }
            }
          }
        } */
        //this.htmlToDisplayDeviceList.innerHTML = "";
        //this.htmlToDisplayDeviceList.appendChild(tbl);
      }
    
    /*sendRandomMessageToServer() {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"vppId": "default","after": "2001-10-19 10:23:54" })
        Util.sendRequestToServer("/energy","POST",data,headers)
    } */
      


}
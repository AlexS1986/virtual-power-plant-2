"use strict"

class TotalPowerBoard {
    constructor(time,desiredPowers,htmlElementToPlotInto, htmlFormElementThatProvidesDesiredPower) {
        this.time = time
        this.desiredPowers = desiredPowers
        this.currentPowers = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0,]
        this.htmlElementToPlotInto = htmlElementToPlotInto
        this.attachToFormElement(htmlFormElementThatProvidesDesiredPower)
    }


    updateCurrentPower(currentPower)  {
        for (let i=0; i< this.time.length-1; i++) {
            this.currentPowers[i] = this.currentPowers[i+1]
        }
        this.currentPowers[this.currentPowers.length-1] = currentPower;
    }

    shiftCurrentPowers() {
        for (let i=0; i< this.time.length-1; i++) {
            this.currentPowers[i] = this.currentPowers[i+1]
        }
        this.currentPowers[this.currentPowers.length-1] = this.currentPowers[this.currentPowers.length-2];
    }

    updateDesiredPower(desiredPower)  {
        for (let i=0; i< this.time.length-1; i++) {
            this.desiredPowers[i] = this.desiredPowers[i+1]
        }
        this.desiredPowers[this.desiredPowers.length-1] = desiredPower;
    }

    shiftDesiredPowers() {
        for (let i=0; i< this.time.length-1; i++) {
            this.desiredPowers[i] = this.desiredPowers[i+1]
        }
        this.desiredPowers[this.desiredPowers.length-1] = this.desiredPowers[this.desiredPowers.length-2];
    }

    plotTotalPowerBoard() {
        Plotly.newPlot( this.htmlElementToPlotInto, [{
            x: this.time,
            y: this.desiredPowers,
            name: "Desired Power Output" }, 
            {
            x: this.time,
            y: this.currentPowers,
            name: "Current Power Output" }
        ], {
        margin: { t: 0 } } );
        this.shiftDesiredPowers()
    }


    attachToFormElement(htmlFormElementThatProvidesDesiredPower) { // TODO needs to post to twin to communicate desired power output
        htmlFormElementThatProvidesDesiredPower.attachedTotalPowerBoard = this
        htmlFormElementThatProvidesDesiredPower.addEventListener('submit', function(ev) {
            ev.currentTarget.attachedTotalPowerBoard.updateDesiredPower(this[0].value)
            console.log("Desired power set to: "+this[0].value)
            ev.preventDefault()
        },false);
    }

    sendDataToServer(vppId)  {
        var headers = {"Content-Type" : "application/json"}
            //var data = JSON.stringify({"deviceId": deviceId,"groupId": groupId, "desiredChargeStatus" : desiredChargeStatus })
        var data = JSON.stringify({"vppId": vppId, "desiredEnergyOutput": this.desiredPowers[this.desiredPowers.length-1], "priority": 2})
        Util.sendRequestToServer("/vpp/"+vppId+"/desired-total-energy-output","POST",data,headers) // TODO maybe currentEnergyOutput should be determined internally
    }

    getDataFromServer(vppId,before,after) {
        function energyDepositResponseHandler() { // TODO declare as method?
            if (this.readyState == 4) {
                if (this.status == 200) {
                    if (this.responseText != null) {
                        var o = JSON.parse(this.response)
                        if(Object.keys(o).length === 0 && o.constructor === Object) { // test if response is an empty object
                            //empty
                        } else {
                            var energyDepositDataFromServer = o
                            const totalPowerBoard = this.myParameters
                            if(energyDepositDataFromServer.hasOwnProperty("energyDeposited")) {
                                totalPowerBoard.updateCurrentPower(energyDepositDataFromServer.energyDeposited)
                            } else {
                                totalPowerBoard.updateCurrentPower(0.0)
                            }
                            
                            //vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer,"default")
                            //vppOverview.plot()

                            // handle results from readside
                            /*var deviceDataFromServer = o.devicesAndTemperatures
                            vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer)
                            vppOverview.plot()*/
                        }
                        /*var deviceDataFromServer = o.devicesAndTemperatures
                        vppOverview.postUpdateOfDeviceDataFromServer(deviceDataFromServer)
                        vppOverview.plot() */
                    } else alert("Communication error: No data received")
                } else alert("Communication error: " + this.statusText)
            }
        }

        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"vppId": vppId,"before": before,"after": after })
        let beforeTest = before.replace(" ","T")
        let afterTest = after.replace(" ","T")
        Util.sendRequestToServer("/vpp/"+vppId+"/energies?before="+beforeTest+"&after="+afterTest,"GET",null,{"Content-Type" : "application/json"},energyDepositResponseHandler,this)
        //Util.sendRequestToServer("/energy","POST",data,headers,energyDepositResponseHandler,this) // has to be POST if you want to send data in body
    }

}
"use strict"

class TotalEnergyOutputBoard {
    constructor(depositCounter,desiredTotalEnergyDeposits,htmlElementToDisplay, htmlFormElementThatProvidesDesiredPower,htmlElementThatHoldsDesiredTotalEnergyOutput,htmlElementThatHoldsCurrentTotalEnergyOutput,groupId) {
        this.depositCounter = depositCounter
        this.desiredTotalEnergyDeposits = desiredTotalEnergyDeposits
        this.currentTotalEnergyDeposits = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0,]
        this.htmlElementToDisplay = htmlElementToDisplay
        this.attachToFormElement(htmlFormElementThatProvidesDesiredPower)
        this.htmlElementThatHoldsDesiredTotalEnergyOutput = htmlElementThatHoldsDesiredTotalEnergyOutput
        this.htmlElementThatHoldsCurrentTotalEnergyOutput = htmlElementThatHoldsCurrentTotalEnergyOutput
        this.groupId = groupId
        this.relaxationParameter = 1.0
    }

    setRelaxationParameter(relaxationParameter) {
        this.relaxationParameter = relaxationParameter
    }

    updateCurrentPower(currentPower)  {
        for (let i=0; i< this.depositCounter.length-1; i++) {
            this.currentTotalEnergyDeposits[i] = this.currentTotalEnergyDeposits[i+1]
        }
        this.currentTotalEnergyDeposits[this.currentTotalEnergyDeposits.length-1] = currentPower;
        this.htmlElementThatHoldsCurrentTotalEnergyOutput.innerHTML = currentPower
    }

    shiftcurrentTotalEnergyDeposits() {
        for (let i=0; i< this.depositCounter.length-1; i++) {
            this.currentTotalEnergyDeposits[i] = this.currentTotalEnergyDeposits[i+1]
        }
        this.currentTotalEnergyDeposits[this.currentTotalEnergyDeposits.length-1] = this.currentTotalEnergyDeposits[this.currentTotalEnergyDeposits.length-2];
    }

    updateDesiredPower(desiredPower)  {
        for (let i=0; i< this.depositCounter.length-1; i++) {
            this.desiredTotalEnergyDeposits[i] = this.desiredTotalEnergyDeposits[i+1]
        }
        this.desiredTotalEnergyDeposits[this.desiredTotalEnergyDeposits.length-1] = desiredPower;
        this.htmlElementThatHoldsDesiredTotalEnergyOutput.innerHTML = desiredPower
    }

    shiftdesiredTotalEnergyDeposits() {
        for (let i=0; i< this.depositCounter.length-1; i++) {
            this.desiredTotalEnergyDeposits[i] = this.desiredTotalEnergyDeposits[i+1]
        }
        this.desiredTotalEnergyDeposits[this.desiredTotalEnergyDeposits.length-1] = this.desiredTotalEnergyDeposits[this.desiredTotalEnergyDeposits.length-2];
    }

    plot() {
        var layout = {
            title: {
              text:'Trailing total energy output',
              font: {
                size: 14
              },
              xref: 'paper',
              x: 0.05,
            },
            xaxis: {
              title: {
                text: 'Period',
                font: {
                  size: 14,
                  color: '#7f7f7f'
                }
              },
            },
            yaxis: {
                title: {
                  text: 'Energy deposited [kWh]',
                  font: {
                    size: 14,
                    color: '#7f7f7f'
                  }
                }
              }
            };

        var trace1 = {
                x: this.depositCounter,
                y: this.desiredTotalEnergyDeposits,
                name: 'Desired Total Energy Output [kWh]',
                type: 'scatter'
              }; 
        
        var trace2 = {
                x: this.depositCounter,
                y: this.currentTotalEnergyDeposits,
                name: 'Current Total Energy Output [kWh]',
                type: 'scatter'
              }; 

        var data = [trace1, trace2];

        Plotly.newPlot(this.htmlElementToDisplay, data, layout)

        /*Plotly.newPlot( this.htmlElementToDisplay, [{
            x: this.depositCounter,
            y: this.desiredTotalEnergyDeposits,
            name: "Desired Total Energy Output [kWh]" }, 
            {
            x: this.depositCounter,
            y: this.currentTotalEnergyDeposits,
            name: "Current Total Energy Output [kWh]" }
        ], {
        margin: { t: 0 } } ); */
        this.shiftdesiredTotalEnergyDeposits()
    }


    attachToFormElement(htmlFormElementThatProvidesDesiredPower) { // TODO needs to post to twin to communicate desired power output
        htmlFormElementThatProvidesDesiredPower.attachedTotalPowerBoard = this
        htmlFormElementThatProvidesDesiredPower.addEventListener('submit', function(ev) {
            ev.currentTarget.attachedTotalPowerBoard.updateDesiredPower(this[0].value)
            ev.currentTarget.attachedTotalPowerBoard.setRelaxationParameter(this[1].value)
            console.log("Desired total energy Output set to: "+this[0].value)
            ev.preventDefault()
            ev.currentTarget.attachedTotalPowerBoard.sendDataToServer()
        },false);
    }

    sendDataToServer()  {
        var headers = {"Content-Type" : "application/json"}
        var data = JSON.stringify({"groupId": this.groupId, "desiredEnergyOutput": parseFloat(this.desiredTotalEnergyDeposits[this.desiredTotalEnergyDeposits.length-1]), "relaxationParameter": parseFloat(this.relaxationParameter)})
        Util.sendRequestToServer("/vpp/"+this.groupId+"/desired-total-energy-output","POST",data,headers) // TODO maybe currentEnergyOutput should be determined internally
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
                        }
                    } else alert("Communication error: No data received")
                } else alert("Communication error: " + this.statusText)
            }
        }
        let beforeTest = before.replace(" ","T")
        let afterTest = after.replace(" ","T")
        Util.sendRequestToServer("/vpp/"+vppId+"/energies?before="+beforeTest+"&after="+afterTest,"GET",null,{"Content-Type" : "application/json"},energyDepositResponseHandler,this)
    }

}
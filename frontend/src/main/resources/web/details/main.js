$(document).ready(function () {
        'use strict';

        const time = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
        const lastTenDeliveredEnergyReadings = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        const htmlElementToPlot = document.getElementById('last-ten-energy-deposits');

        function plotLastTenEnergyDeposits(htmlElementToPlot,time, lastTenDeliveredEnergyReadings) {
            //https://plotly.com/javascript/figure-labels/
            var layout = {
                title: {
                  text:'Last ten energy deposits',
                  font: {
                    family: 'Courier New, monospace',
                    size: 14
                  },
                  xref: 'paper',
                  x: 0.05,
                },
                xaxis: {
                  title: {
                    text: '#number',
                    font: {
                      family: 'Courier New, monospace',
                      size: 14,
                      color: '#7f7f7f'
                    }
                  },
                },
                yaxis: {
                    title: {
                      text: 'Energy deposited [KWh]',
                      font: {
                        family: 'Courier New, monospace',
                        size: 14,
                        color: '#7f7f7f'
                      }
                    }
                  }
                };

                var trace1 = {
                    x: time,
                    y: lastTenDeliveredEnergyReadings,
                    name: 'Name of Trace 1',
                    type: 'scatter'
                  };  
                
                var data = [trace1];

            Plotly.newPlot( htmlElementToPlot, data, layout );
        }

        plotLastTenEnergyDeposits(htmlElementToPlot, time,lastTenDeliveredEnergyReadings)

        // TODO make function setDesiredChargeStatus(desiredChargeStatus)
        const form = document.forms.namedItem("desiredChargeStatus");
        form.addEventListener('submit', function(ev) {
            const desiredChargeStatus = this[0].value / 100.0
            const deviceId = this.getAttribute("deviceId")
            const groupId = this.getAttribute("groupId")
            var headers = {"Content-Type" : "application/json"}
            var data = JSON.stringify({"desiredChargeStatus" : desiredChargeStatus })
            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId + "/charge-status","POST",data,headers)
            ev.preventDefault()
        },false);

        var releaseManualControlButton = document.getElementById('release-manual-button')
        releaseManualControlButton.onclick = function (event) {
            const deviceId = event.currentTarget.getAttribute("deviceId")
            const groupId = event.currentTarget.getAttribute("groupId")

            var headers = {"Content-Type" : "application/json"}
            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId + "/charge-status","DELETE",null,headers)
            
        }

        function refresh() {
            function dataFromServerHandler() { // TODO function definitions outside of loop?
                if (this.readyState == 4) {
                    if (this.status == 200) {
                        if (this.responseText != null) {

                            // plot device data TODO make function
                            var deviceDataFromServerJson = JSON.parse(this.response)
                            
                            const dataDiv = document.getElementById("charge-status-div")
                            dataDiv.innerHTML = deviceDataFromServerJson.data*100.0
                            
                            const hostDiv = document.getElementById("host-div")
                            hostDiv.innerHTML = deviceDataFromServerJson.currentHost

                            const priorityDiv = document.getElementById("priority-div")
                            priorityDiv.innerHTML = deviceDataFromServerJson.priority

                            plotLastTenEnergyDeposits(htmlElementToPlot,time,deviceDataFromServerJson.lastTenDeliveredEnergyReadings)

                        } else alert("Communication error: No data received")
                    } else alert("Communication error: " + this.statusText)
                }
            }

            var headers = {}
            const deviceId = document.getElementById("release-manual-button").getAttribute("deviceId")
            const groupId = document.getElementById("release-manual-button").getAttribute("groupId")

            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId,"GET",null,headers,dataFromServerHandler)
        }

        refresh()
        const intervalId = setInterval(refresh, 1 * 2000)
    }
)
/**
 * defines the dynamic behavior of the device-details page of the GUI
 */
$(document).ready(function () {
        'use strict';

        /**
         * prepare plot for the last ten energy deposits
         */
        const time = [-10, -9, -8, -7, -6, -5, -4, -3, -2, -1];
        const lastTenDeliveredEnergyReadings = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        const htmlElementToPlot = document.getElementById('last-ten-energy-deposits');

        /**
         * auxiliary function that displays the last ten energy deposits
         * @param {*} htmlElementToPlot 
         * @param {*} time 
         * @param {*} lastTenDeliveredEnergyReadings 
         */
        function plotLastTenEnergyDeposits(htmlElementToPlot,time, lastTenDeliveredEnergyReadings) {
            //https://plotly.com/javascript/figure-labels/
            var layout = {
                title: {
                  text:'Last ten energy deposits',
                  font: {
                    size: 14
                  },
                  xref: 'paper',
                  x: 0.05,
                },
                xaxis: {
                  title: {
                    text: '#number',
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
                        //family: 'Courier New, monospace',
                        size: 14,
                        color: '#7f7f7f'
                      }
                    }
                  }
                };

                var trace1 = {
                    x: time,
                    y: lastTenDeliveredEnergyReadings,
                    name: 'last ten delivered energy readings',
                    type: 'scatter'
                  };  
                
                var data = [trace1];

            Plotly.newPlot( htmlElementToPlot, data, layout );
        }

        // plot the last ten energy deposits
        plotLastTenEnergyDeposits(htmlElementToPlot, time,lastTenDeliveredEnergyReadings)

        // attach event listener that communicates a desired charge status to the server
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

        // attach event listener that release the manual control priority, so that the charge status can be controlled by DeviceGroup again
        var releaseManualControlButton = document.getElementById('release-manual-button')
        releaseManualControlButton.onclick = function (event) {
            const deviceId = event.currentTarget.getAttribute("deviceId")
            const groupId = event.currentTarget.getAttribute("groupId")

            var headers = {"Content-Type" : "application/json"}
            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId + "/charge-status","DELETE",null,headers)
            
        }

        
        /**
         * define the periodic refresh of details page of the GUI
         */
        function refresh() {

            /**
             * defines how the response from the server should be used to update the details page
             */
            function dataFromServerHandler() { 
                if (this.readyState == 4) {
                    if (this.status == 200) {
                        if (this.responseText != null) {

                            // plot device data TODO make function
                            var deviceDataFromServerJson = JSON.parse(this.response)
                            
                            const dataDiv = document.getElementById("charge-status-div")
                            dataDiv.innerHTML = deviceDataFromServerJson.chargeStatus*100.0
                            
                            const hostDiv = document.getElementById("host-div")
                            hostDiv.innerHTML = deviceDataFromServerJson.currentHost

                            const priorityDiv = document.getElementById("priority-div")
                            priorityDiv.innerHTML = deviceDataFromServerJson.priority

                            plotLastTenEnergyDeposits(htmlElementToPlot,time,deviceDataFromServerJson.lastTenDeliveredEnergyReadings)

                        } else alert("Communication error: No data received")
                    } else alert("Communication error: " + this.statusText)
                }
            }

            // send request for the individual state of the device to the server
            var headers = {}
            const deviceId = document.getElementById("release-manual-button").getAttribute("deviceId")
            const groupId = document.getElementById("release-manual-button").getAttribute("groupId")
            Util.sendRequestToServer("/vpp/device/"+groupId+"/"+deviceId,"GET",null,headers,dataFromServerHandler)
        }

        refresh()
        const intervalId = setInterval(refresh, 1 * 2000) // repeat every 2 seconds
    }
)
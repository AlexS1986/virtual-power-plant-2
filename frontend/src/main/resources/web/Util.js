"use strict"

class Util {
    static sendRequestToServer(route,method,data,headers,readyStateChangeHandlerFunction=Util.defaultReadyStateChangeFunction,myParameters=null) {
        var oReq = new XMLHttpRequest(); 
        oReq.open(method, route, true);
        oReq.myParameters = myParameters

        for (var key in headers) {
            oReq.setRequestHeader(key,headers[key])
        }
        oReq.onreadystatechange = readyStateChangeHandlerFunction
        
        if(data != null) {
            oReq.send(data)
        } else {
            oReq.send()
        }     
    }

    static defaultReadyStateChangeFunction() {
        if (this.readyState == 4) {
            if (this.status == 200) {
                if (this.responseText != null) {
                    console.log(this.responseText)
                } else alert("Communication error: No data received")
            } else alert("Communication error: " + this.statusText)
        }
    }
}
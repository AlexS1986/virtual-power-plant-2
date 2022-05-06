#!/bin/bash
kubectl config set-context --current --namespace=iot-system-1
http_port_twin=$(kubectl get service | grep "iot-system-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')
numberOfDevices=100
http_port_frontend=$(kubectl get service | grep "frontend-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')



echo "<<< Clean up after test >>>"
echo " "
echo "<<< Stopping devices >>>"
for i in `seq 0 $numberOfDevices`
do
    deviceName="device$i"
    stopString='vpp/device/default/'$deviceName
    echo $(curl -s -XDELETE http://192.168.49.2:$http_port_frontend/$stopString) 
done

sleep 1m

for i in `seq 0 $(($numberOfDevices-1))`
do
    deviceName="device$i"
    echo "Try to untrack $deviceName."
    echo $(curl -s -XPOST http://192.168.49.2:$http_port_twin/twin/untrack-device -H "Content-Type: application/json" --data "{\"groupId\":\"default\",\"deviceId\": \"$deviceName\"}")
    sleep 0.02s
done
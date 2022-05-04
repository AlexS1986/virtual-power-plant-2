#!/bin/bash
#call as ./performance.sh not as sh performance.sh https://stackoverflow.com/questions/2462317/bash-syntax-error-redirection-unexpected

kubectl config set-context --current --namespace=iot-system-1
http_port_frontend=$(kubectl get service | grep "frontend-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')
http_port_twin=$(kubectl get service | grep "iot-system-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')

numberOfDevices=3000
numberRequests=10
timeAllowedPerRequest=2.0
timeAllowedForUpdateToSingleDeviceRegisteredAtServer=2.0

echo "<<< Starting $numberOfDevices devices >>>"


notAllFound=1
while (( $(echo "$notAllFound") ))
do
    notAllFound=0
    response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
    #echo $response
    for i in `seq 0 $(($numberOfDevices-1))`
    do
        deviceName="device$i"
        if  ! grep -q "$deviceName" <<< "$response"; then
            echo "Response does not contain $deviceName try to start again."
            notAllFound=1
            deviceName="device$i"
            startString='simulator/default/'$deviceName'/start'
            echo $(curl -s -XPOST http://192.168.49.2:$http_port_frontend/$startString)
        fi
    done

    #starting additional device for update time testing
    deviceName="deviceUpdateTime"
    if  ! grep -q "$deviceName" <<< "$response"; then
            echo "Response does not contain $deviceName try to start again."
            notAllFound=1
            echo $(curl -s -XPOST http://192.168.49.2:$http_port_twin/twin/track-device -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "deviceUpdateTime"}')
    fi

    sleep 15s
done

sleep 30s

echo "<<< Begin testing. >>"
echo " "


sum=0.0
allFound=true
containsTimedOut=false

echo "<<< Requesting device data at server $numberRequests times. >>>"
echo " "

for j in `seq 0 $(($numberRequests-1))`
do
    # get server time for request
    response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
    #echo $response
    numberTwinPods=$(kubectl get pod | grep -c 'twin-[[:alnum:]]\{9\}-[[:alnum:]]\{5\}')
    echo "The number of twin pods is $numberTwinPods for request $(($j+1))."
    #https://stackoverflow.com/questions/40519902/how-to-store-value-from-grep-output-in-variable
    time_starttransfer="$(echo $response | grep -Eo 'time_starttransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
    time_starttransfer="$(echo "$time_starttransfer" | sed 's/,/./;')"

    time_pretransfer="$(echo $response | grep -Eo 'time_pretransfer [0-9]+\,[0-9]+' | grep -Eo '[0-9]+\,[0-9]+')"
    time_pretransfer="$(echo "$time_pretransfer" | sed 's/,/./;')"

    time_at_server_this_request=`echo|awk -v a1=$time_starttransfer -v a2=$time_pretransfer '{print a1-a2}'`
    echo "Server processing time for request $(($j+1)) "$time_at_server_this_request"s"
    sum=`echo|awk -v y1=$time_starttransfer -v y2=$time_pretransfer -v y3=$sum '{print y1-y2+y3}'`

    for i in `seq 0 $(($numberOfDevices-1))`
    do
        deviceName="device$i"
        if  ! grep -q "$deviceName" <<< "$response"; then
            echo "Response does not contain $deviceName"
            allFound=false
            break
        fi
    done

    if  grep -q "device timed out" <<< "$response"; then
            echo "Request $j contains \"device timed out\" message."
            containsTimedOut=true
    fi
    
    sleep 5s
done
average=$(echo $sum/$numberRequests | node -p)


echo "<<< Check how long it takes to record update at device >>>"
echo " "
currentDateTime="$(date +"%Y-%m-%d %T" )"
updateNotRegistered=1

echo $(curl -s -XPOST http://192.168.49.2:$http_port_twin/twin/data -H "Content-Type: application/json" --data "{\"groupId\":\"default\",\"deviceId\": \"deviceUpdateTime\", \"capacity\" : 100,\"chargeStatus\": 0.8, \"deliveredEnergy\" : 0, \"deliveredEnergyDate\":\"$currentDateTime\"}")
START=$(date +%s.%N)
while (( $(echo "$updateNotRegistered") ))
do
    response=$(curl -s -XGET http://192.168.49.2:$http_port_twin/twin/data -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "deviceUpdateTime"}')
    if  grep -q "0.8" <<< "$response"; then
            END=$(date +%s.%N)
            echo "Charge status update registered at deviceUpdateTime"
            updateNotRegistered=0
    fi
done
timeUntilDeviceIsUpdated=$(echo "$END - $START" | bc |  sed 's/^\./0./')


echo "<<< Clean up after test >>>"
echo " "
echo "<<< Stopping devices >>>"
for i in `seq 0 $numberOfDevices`
do
    deviceName="device$i"
    stopString='vpp/device/default/'$deviceName
    echo $(curl -s -XDELETE http://192.168.49.2:$http_port_frontend/$stopString) 
done

# stop test device
deviceUpdateTimeFound=1
while (( $(echo "$deviceUpdateTimeFound") ))
do
    response=$(curl -s -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
    deviceName="deviceUpdateTime"
    deviceUpdateTimeFound=0
    if  grep -q "$deviceName" <<< "$response"; then
            echo "Response contains $deviceName try to stop."
            deviceUpdateTimeFound=1
            echo $(curl -s -XPOST http://192.168.49.2:$http_port_twin/twin/untrack-device -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "deviceUpdateTime"}')
    fi
    sleep 1s
done

# clean database twin readside
currentDateTime="$(date +"%Y-%m-%d %T" )"
echo $(curl -s -XPOST http://192.168.49.2:$http_port_frontend/vpp/default/energies/delete -H "Content-Type: application/json" --data "{\"before\":\"$currentDateTime\"}")

echo " "
echo "<<< Checking Performance of Server Reply >>>"
total=true
echo " "
echo "Average time required to compute server response "$average"s allowed" $timeAllowedPerRequest"s"
echo " "
timeOk=false
if (( $(echo "$timeAllowedPerRequest > $average" |bc -l) )); then
  timeOk=true
fi
if [[ $total && $timeOk ]]; then
    total=true
else
    total=false
fi
#echo $total
echo "Time until a single devices update has been registered at server: $timeUntilDeviceIsUpdated""s allowed" $timeAllowedForUpdateToSingleDeviceRegisteredAtServer"s" 
echo " "
timeOkDevice=false
if (( $(echo "$timeAllowedForUpdateToSingleDeviceRegisteredAtServer > $timeUntilDeviceIsUpdated" |bc -l) )); then
  timeOkDevice=true
fi
if [[ $total && $timeOkDevice ]]; then
    total=true
else
    total=false
fi
#echo $total
echo "<<< Checking Correctness of Server Reply>> "
echo "Data has been found for all started devices: $allFound"
echo " "
if [[ $total && $allFound ]]; then
    total=true
else
    total=false
fi
#echo $total
echo "Server response contains \"device timed out\" message: $containsTimedOut. Expected false."
echo " "
if [[ "$total" == "true" ]] && [[ "$containsTimedOut" == "false" ]]; then
    total=true
else
    total=false
fi
#echo $total

echo "<<< Checking Success Criterions >>>"
if $total;
then
    echo "<<< TEST PASSED >>>"
else
    echo "<<< TEST FAILED >>>"
fi;


# idea add a single device post a status and observe how long it takes to update status



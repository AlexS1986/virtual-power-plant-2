#!/bin/bash
#call as ./performance.sh not as sh performance.sh https://stackoverflow.com/questions/2462317/bash-syntax-error-redirection-unexpected

export http_port_frontend=$(kubectl get service | grep "frontend-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}')

numberOfDevices=20
numberRequests=10
timeAllowedPerRequest=0.15

echo "<<< Starting $numberOfDevices devices >>>"
for i in `seq 0 $(($numberOfDevices-1))`
do
    deviceName="device$i"
    startString='simulator/default/'$deviceName'/start'
    echo $(curl -s -XPOST http://192.168.49.2:$http_port_frontend/$startString)
done
echo " "
# wait for devices to be launched
sleep 5s



sum=0.0
allFound=true
echo "<<< Requesting device data at server $numberRequests times."
echo " "

for j in `seq 0 $(($numberRequests-1))`
do
    # get server time for request
    response=$(curl -s -w 'time_starttransfer %{time_starttransfer} time_pretransfer %{time_pretransfer}' -XGET http://192.168.49.2:$http_port_frontend/vpp/default)
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
done
average=$(echo $sum/$numberRequests | node -p)

echo " "
echo "<<< Stopping devices >>>"
for i in `seq 0 $numberOfDevices`
do
    deviceName="device$i"
    stopString='vpp/device/default/'$deviceName
    echo $(curl -s -XDELETE http://192.168.49.2:$http_port_frontend/$stopString) 
done
echo " "

echo "<<< Checking Performance of Server Reply >>>"
echo "Average time required to compute server response "$average"s allowed" $timeAllowedPerRequest"s"
echo " "
echo "<<< Checking Correctness of Server Reply>> "
echo "Data has been found for all started devices: $allFound"
echo " "
echo "<<< Checking Success Criterions >>>"
timeOk=$(echo "$timeAllowedPerRequest > $average" |bc -l)
 if (( timeOk )) && $allFound;
 then
    echo "<<TEST PASSED>>"
 else
    echo "TEST FAILED"
 fi;




kubectl config set-context --current --namespace=simulator
#kubectl delete service readside-service
sed -i 's/replicas: 1/replicas: 0/g' kubernetes/simulator.yml
kubectl apply -f kubernetes/simulator.yml
sbt -Ddocker.username=alexs1986 docker:publish
sed -i 's/replicas: 0/replicas: 1/g' kubernetes/simulator.yml
kubectl apply -f kubernetes/simulator.yml

export http_port=$(kubectl get service | grep "simulator-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}') #https://www.cyberciti.biz/faq/grep-regular-expressions/
echo $http_port
#echo $(curl -XPOST http://192.168.49.2:$http_port/simulator/charge-status -H "Content-Type: application/json" --data '{"groupId":"default","deviceId": "device1", "desiredChargeStatus" : 1.0}')
sleep 10s
#kubectl expose deployment readside --type=LoadBalancer --name=readside-service
sleep 1s
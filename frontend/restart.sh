#!/bin/bash
## This script deploys the simulator container of the simulator application
kubectl config set-context --current --namespace=iot-system-1

sed -i 's/replicas: 1/replicas: 0/g' kubernetes/frontend.yml
kubectl apply -f kubernetes/frontend.yml
sbt -Ddocker.username=alexs1986 docker:publish
sed -i 's/replicas: 0/replicas: 1/g' kubernetes/frontend.yml
kubectl apply -f kubernetes/frontend.yml
sleep 10s
#expose service manually if desired
#kubectl delete service frontend-service
#kubectl expose deployment frontend --type=LoadBalancer --name=frontend-service
sleep 1s

# tests
export http_port=$(kubectl get service | grep "frontend-service" | grep "8080" | grep  -o '8080:[^/TCP]\+' | grep -o '3[[:digit:]]\{4\}') #https://www.cyberciti.biz/faq/grep-regular-expressions/
export parameters='?before=2010-10-19T10:23:54&after=2001-10-19T10:23:54'
echo $(curl -v -XGET http://192.168.49.2:$http_port/vpp/default/energies$parameters)

# End-to-end tests
# testcafe chrome:headless src/main/resources/e2e-tests/
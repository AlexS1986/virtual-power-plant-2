# virtual-power-plant

This prototype application is inspired by Tesla's Virtual Power Plant. It simulates home-installed batteries and allows to aggregate them to a conceptual power-plant like entity, that can be controlled via a webinterface.

In order to run the application:

1. get access to a running Kubernetes cluster, e.g. a local minikube installation suffices
2. install two postgresql pods (postgresql and postgresql-readside in namespaces postgresql and postgresql-readside respectively), e.g. via helm
3. create Kubernetes secrets for the credentials in namespace iot-system-1

kubectl create secret -n iot-system-1 generic postgresql-env \
--from-literal=postgresql_username=postgres \
--from-literal=postgresql_password=$POSTGRES_PASSWORD \
--from-literal=postgresql_url=jdbc:postgresql://postgresql.postgresql.svc.cluster.local:5432/

kubectl create secret -n iot-system-1 generic postgresql-readside-env \
--from-literal=postgresql_username_readside=postgres \
--from-literal=postgresql_password_readside=$POSTGRES_PASSWORD_READSIDE \
--from-literal=postgresql_url_readside=jdbc:postgresql://postgresql-readside.postgresql-readside.svc.cluster.local:5432/

4. install the database tables in twin/create_user_tables.sql twin/create_journal_tables_only_ws_event_scing.sql in the postgresql-readside database
5. install the database tables twin/create_journal_tables_only_ws_event_scing.sql in the postgresql database
6. install the buildtool sbt and Docker
7. run the installation script twin/restart.sh simulator/restart.sh twin-readside/restart.sh frontend/restart.sh
8. open minikubeIP:30408 in the browser, there minikubeIP is the IP of the minikube cluster or the external ip of the frontend microservice's external service
9. Navigate the web interface to test the application

#####################################################

The folder UML-Diagrams contains several UML diagrams that document the structure and behavior of the IoT prototype and the simulator application. The diagrams can be opened with the freely testable software StarUML, see https://staruml.io. 

The diagrams are organized in Requirement-, Analysis-, and Design diagrams which become increasingly more detailed.

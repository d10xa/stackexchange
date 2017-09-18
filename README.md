## StackExchange tags aggregator

edit [application.conf](src/main/resources/application.conf) to set up proxy or stub

run

    ./gradlew run
    ./gradlew runStub # stackexchange stub

test

    http :8080/search tag==java tag==scala tag==kotlin

    http http://api.stackexchange.com/2.2/search\?pagesize\=100\&order\=desc\&sort\=creation\&tagged\=scala\&site\=stackoverflow\&filter\=\!Tht3Tcqv9 | jq '.items[] | .is_answered' | sort | uniq -c

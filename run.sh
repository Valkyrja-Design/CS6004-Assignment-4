#!/bin/bash

javac -g ./testcases/*.java -d ./testcases
javac -cp .:sootclasses-trunk-jar-with-dependencies.jar PA4.java
java -cp .:sootclasses-trunk-jar-with-dependencies.jar PA4 "$@"
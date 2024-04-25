#!/bin/bash

./run.sh
j="j2re-image/bin/java"
time $j -Xint -cp $1 $2
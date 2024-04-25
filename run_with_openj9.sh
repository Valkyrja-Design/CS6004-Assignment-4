#!/bin/bash

./run_analysis.sh
j="j2re-image/bin/java"
time $j -Xint -cp $1 $2
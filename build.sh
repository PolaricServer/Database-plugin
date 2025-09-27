#!/bin/bash

mvn clean dependency:copy-dependencies -DoutputDirectory=lib -DincludeScope=runtime package 

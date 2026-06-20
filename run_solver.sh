#!/bin/bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew :app:testDebugUnitTest --tests "*SolverHarness" -PsolverOutput --console=plain "$@"

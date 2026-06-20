#!/bin/bash
# Regenerate docs/solver-baseline.md: 10 seeds per weekday, haversine distances.
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew :app:testDebugUnitTest --tests "*SolverHarness.baselineAcrossSeeds" \
    -PsolverOutput -Dsolver.baseline=1 --console=plain

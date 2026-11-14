#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/tests
java -jar tools/ecj.jar -11 -warn:none -cp tools/json.jar -d build/tests \
    src/com/velpro/checkin/Net.java tests/com/velpro/checkin/NetTest.java
java -cp build/tests:tools/json.jar com.velpro.checkin.NetTest
java -jar tools/ecj.jar -11 -warn:none -cp build/tests:tools/json.jar -d build/tests \
    src/com/velpro/checkin/WorkBuddy.java src/com/velpro/checkin/Trae.java \
    src/com/velpro/checkin/AccountStore.java src/com/velpro/checkin/SiteRegistry.java \
    src/com/velpro/checkin/RunCoordinator.java src/com/velpro/checkin/CustomSite.java \
    src/com/velpro/checkin/Balances.java src/com/velpro/checkin/History.java \
    tests/com/velpro/checkin/WorkBuddyTest.java tests/com/velpro/checkin/TraeTest.java \
    tests/com/velpro/checkin/FrameworkTest.java tests/com/velpro/checkin/CustomSiteTest.java \
    tests/com/velpro/checkin/BalancesTest.java
java -cp build/tests:tools/json.jar com.velpro.checkin.WorkBuddyTest
java -cp build/tests:tools/json.jar com.velpro.checkin.TraeTest
java -cp build/tests:tools/json.jar com.velpro.checkin.FrameworkTest
java -cp build/tests:tools/json.jar com.velpro.checkin.CustomSiteTest
java -cp build/tests:tools/json.jar com.velpro.checkin.BalancesTest
java -jar tools/ecj.jar -11 -warn:none -cp build/tests:tools/json.jar -d build/tests \
    tests/com/velpro/checkin/OAuthTest.java
java -cp build/tests:tools/json.jar com.velpro.checkin.OAuthTest
java -jar tools/ecj.jar -11 -warn:none -cp build/tests:tools/json.jar -d build/tests tests/com/velpro/checkin/ReliabilityTest.java
java -cp build/tests:tools/json.jar com.velpro.checkin.ReliabilityTest
java -jar tools/ecj.jar -11 -warn:none -cp build/tests:tools/json.jar -d build/tests tests/com/velpro/checkin/RunTest.java
java -cp build/tests:tools/json.jar com.velpro.checkin.RunTest
if [ -f tests/com/velpro/checkin/HistoryTest.java ]; then
    java -jar tools/ecj.jar -11 -warn:none -cp tools/json.jar -d build/tests \
        src/com/velpro/checkin/History.java tests/com/velpro/checkin/HistoryTest.java
    java -cp build/tests:tools/json.jar com.velpro.checkin.HistoryTest
fi
python3 tests/android_contract_test.py
python3 tests/ui_contract_test.py

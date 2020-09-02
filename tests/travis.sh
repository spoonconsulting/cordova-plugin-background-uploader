#!/bin/bash
set -o nounset
set -o errexit

npm install -g cordova@9.0.0 npx@10.2.2 forever@3.0.0
npm install

# lint
npm run lint
# start mock server
cd tests/test-server && mkdir uploads && npm install && npm start
cd ../..
mkdir ~/test_results
# run tests appropriate for platform
if [[ "$TRAVIS_OS_NAME" == "osx" ]]; then
    gem install cocoapods
    pod repo update
    npm install -g ios-sim@9.0.0 ios-deploy@1.10.0
    npm run test:ios
fi
if [[ "$TRAVIS_OS_NAME" == "linux" ]]; then
    echo no | android create avd --force -n test -t android-22 --abi armeabi-v7a
    emulator -avd test -no-audio -no-window &
    android-wait-for-emulator
    npm run test:android
fi
#!/bin/bash
set -o nounset
set -o errexit

cd demo

if [[ "$TRAVIS_OS_NAME" == "linux" ]]; then
  export ANDROID_HOME=/usr/local/android-sdk
  export ANDROID_SDK_ROOT=/usr/local/android-sdk
  export PATH=/usr/local/android-sdk/build-tools/28.0.3/:${PATH}

  bundle exec fastlane android beta  
fi
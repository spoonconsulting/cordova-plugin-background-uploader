#!/bin/bash
set -o nounset
set -o errexit

cd demo

gem install bundler -v 2.1.4
bundle config set deployment 'true'
bundle install

node -v
npm install --unsafe-perm=true --allow-root -g cordova@8.1.2 ionic@5.4.1
npm install
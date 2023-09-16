#!/bin/bash
set -e

case "$1" in
  java8)
    echo "https://github.com/bell-sw/Liberica/releases/download/8u382%2B6/bellsoft-jdk8u382+6-linux-amd64.tar.gz"
  ;;
  java11)
    echo "https://github.com/bell-sw/Liberica/releases/download/11.0.15.1+2/bellsoft-jdk11.0.15.1+2-linux-amd64.tar.gz"
  ;;
  java17)
    echo "https://github.com/bell-sw/Liberica/releases/download/17.0.3.1+2/bellsoft-jdk17.0.3.1+2-linux-amd64.tar.gz"
  ;;
  *)
    echo $"Unknown java version"
    exit 1
esac

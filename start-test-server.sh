#!/bin/bash

set -ex

svnserve -r testdata --listen-host localhost -d --foreground


#!/bin/sh
cd core; lein test; cd ..;
cd interface; lein test; cd ..;
cd nrepl; lein test; cd ..;

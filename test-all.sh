#!/bin/sh
cd base; lein test; cd ..;
cd wingman; lein test; cd ..;
cd nrepl; lein test; cd ..;

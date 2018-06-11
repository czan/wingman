#!/bin/sh
cd base; lein test; cd ..;
cd sugar; lein test; cd ..;
cd nrepl; lein test; cd ..;

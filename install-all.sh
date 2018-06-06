#!/bin/sh
cd core; lein install; cd ..;
cd interface; lein install; cd ..;
cd nrepl; lein install; cd ..;

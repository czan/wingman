#!/bin/sh
cd base; lein install; cd ..;
cd wingman; lein install; cd ..;
cd nrepl; lein install; cd ..;

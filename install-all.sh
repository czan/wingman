#!/bin/sh
cd base; lein install; cd ..;
cd sugar; lein install; cd ..;
cd nrepl; lein install; cd ..;

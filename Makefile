# KindleNotes - notes for the Kindle Keyboard (Kindle 3 Wi-Fi)
#
#   make            build ARM binary + update packages (dist/*.bin)
#   make test       run the test-suite against the native build
#   make test-arm   run the test-suite against the ARM build under qemu-arm-static
#   make clean
#
# Variables: DEVICE=k3w|k3g|k3gb  KINDLETOOL=path  CC_ARM="arm-linux-musleabi-gcc"

DEVICE ?= k3w
export DEVICE KINDLETOOL CC_ARM

.PHONY: all native test test-arm clean

all:
	./build.sh

native: build/notesd-native

build/notesd-native: src/notesd.c
	mkdir -p build
	cc -std=gnu99 -Wall -Wextra -O2 -o $@ $<

test: build/notesd-native
	NOTESD=build/notesd-native tests/run.sh

test-arm:
	NOTESD="qemu-arm-static payload/notes/bin/notesd" PORT=18081 tests/run.sh

clean:
	rm -rf build

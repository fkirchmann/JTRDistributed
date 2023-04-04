#!/usr/bin/env python3

import sys
import vastlib

if len(sys.argv) < 2 or len(sys.argv) > 3:
    print("Usage: {} MACHINE_ID (PRICE)".format(sys.argv[0]))
    exit(1)

if len(sys.argv) == 2:
    vastlib.rent(int(sys.argv[1]))
else:
    vastlib.rent(int(sys.argv[1]), float(sys.argv[2]))

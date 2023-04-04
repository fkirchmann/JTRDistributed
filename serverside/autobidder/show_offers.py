#!/usr/bin/env python3

import urllib
import vastlib
from tabulate import tabulate
from vastlib import vast, log
from functools import cmp_to_key
import json

offers = vastlib.search_offers_ondemand()

table_headers = ["Inst.ID", "H.ID", "Location", "GPUs", "kP/s", "$min", "$max", "$%"]
table_floatfmt = [None    , None  , None      , None  , ".0f" , ".3f" , ".3f" , ".0f" ]
table = []

eligible_machines = 0
eligible_hashpower = 0

for machine in offers:
    hashrate = vastlib.get_hashrate(client_id=machine["id"], host_id=machine["machine_id"],
                                    gpu_name=machine["gpu_name"],
                                    tflops=machine["total_flops"], num_gpus=machine["num_gpus"])

    max_price = vastlib.max_price(hashrate)
    if machine["is_bid"]:
        min_price = machine["min_bid"]
        target_price = vastlib.target_price(hashrate=hashrate, min_bid=machine["min_bid"])
    else:
        min_price = machine["dph_total"]
        target_price = machine["dph_total"]
    min_bid_percent = (min_price / max_price) * 100
    if min_bid_percent <= 100:
        eligible_machines += 1
        eligible_hashpower += hashrate
    if min_bid_percent <= 200:
        table.append([machine["id"], machine["machine_id"], machine["hostname"],
                      str(machine["num_gpus"]) + " * " + machine["gpu_name"], hashrate / 1000,
                      min_price, max_price, min_bid_percent])

# print(json.dumps(offers, indent=4, sort_keys=True))

table = sorted(table, key=lambda a: a[7])
print("")
print(tabulate(table, headers=table_headers, floatfmt=table_floatfmt, tablefmt="github"))


print("")
print("Got {} eligible machines with a total of {:.1f} MP/s".format(eligible_machines, eligible_hashpower / 1000000))

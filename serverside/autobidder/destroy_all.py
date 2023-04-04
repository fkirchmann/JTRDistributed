#!/usr/bin/env python3

import vastlib
import time
from vastlib import log

answer = ""
while answer not in ["Y", "n"]:
    answer = input("Are you SURE you want to destroy all active instances [Y/n]? ")
if answer != "Y":
    exit(1)

while True:
    log("Listing active machines...")
    machines = vastlib.list_machines()
    if len(machines) == 0:
        log("Done! All machines deleted or no machines to delete.")
        break
    for machine in machines:
        log("Destroying instance {}".format(machine["id"]))
        vastlib.destroy_instance(machine["id"])
    log("Sleeping 15 seconds")
    time.sleep(15)
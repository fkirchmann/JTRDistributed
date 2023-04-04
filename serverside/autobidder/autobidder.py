#!/usr/bin/env python3
# NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.

# TODO:
# show r$h% in list
# fix summary r$h% calc.

import os
import sys
import json
import vastlib
from datetime import datetime
import time
from tabulate import tabulate
from vastlib import vast, log

target_hashrate = 200 * 1000 * 1000
max_dph = 50

instances_refresh_delay = 15
offer_search_delay = 60

# If a machine has been active for this amount of time but not produced any results, blacklist it
machine_timeout_blacklist = 4 * 60

do_rent = True

vastlib.log_file_set("log.vast.txt")

log("Starting up")

if len(sys.argv) >= 2 and sys.argv[1] == "--no-rent":
    log("Not renting any machines in this run.")
    do_rent = False

state = {
    "host_active_since": {},
    "host_blacklist": []
}
state_file = "autobidder-state.json"
state_file_tmp = "autobidder-state-tmp.json"

if os.path.isfile(state_file):
    with open(state_file) as json_file:
        state = json.load(json_file)

def save_state():
    with open(state_file_tmp, 'w', encoding='utf-8') as f:
        json.dump(state, f, indent = 4, ensure_ascii = False)
    if os.path.exists(state_file):
        os.remove(state_file)
    os.rename(state_file_tmp, state_file)

def process_offers(offers):
    global total_dph, total_hashrate, total_real_hashrate, new_machines, new_hashrate, new_dph

    for offer in offers:
        offer["hashrate"] = vastlib.get_hashrate(client_id=offer["id"], host_id=offer["machine_id"],
                                                 gpu_name=offer["gpu_name"], tflops=offer["total_flops"],
                                                 num_gpus=offer["num_gpus"])
        max_price_orig = vastlib.max_price(offer["hashrate"])
        # If we can afford this as a fixed rental, do it
        if offer["dph_total"] <= max_price_orig * vastlib.fixed_bonus_factor_maxprice:
            offer["min_price"] = offer["dph_total"]
            offer["target_price"] = offer["dph_total"]
            offer["max_price"] = max_price_orig * vastlib.fixed_bonus_factor_maxprice
            offer["is_bid"] = False
        else:
            offer["min_price"] = offer["min_bid"]
            offer["target_price"] = vastlib.target_price(hashrate=offer["hashrate"], min_bid=offer["min_bid"])
            offer["max_price"] = max_price_orig
            offer["is_bid"] = True
        offer["min_price_percent"] = (offer["min_price"] / offer["max_price"]) * 100
        offer["real_price_percent"] = (offer["min_price"] / max_price_orig) * 100
    offers_sorted = sorted(offers, key=lambda a: a["min_price_percent"])
    for offer in offers_sorted:
        if offer["min_price_percent"] < 101 and offer["target_price"] < max_dph - total_dph \
                and total_hashrate < target_hashrate and total_dph < max_dph \
                and not offer["is_bid"] \
                and not offer["machine_id"] in state["host_blacklist"]:
            new_dph += offer["target_price"]
            total_dph += offer["target_price"]
            new_hashrate += offer["hashrate"]
            total_hashrate += offer["hashrate"]
            new_machines += 1
            log(("Bid " if offer["is_bid"] else "Fixed ") +
                   "Rent: # {}, {} * {}, {:.1f} MP/s, Min.: {:.3f}, Max: {:.3f}, Price: {:.3f} / {:.0f} %"
                .format(offer["id"], offer["num_gpus"], offer["gpu_name"], offer["hashrate"] / 1000000,
                        offer["min_price"], offer["max_price"], offer["target_price"], offer["real_price_percent"]))
            if offer["is_bid"]:
                vastlib.rent(offer["id"], offer["target_price"])
            else:
                vastlib.rent(offer["id"])
            state["host_active_since"].pop(offer["machine_id"], None)
            # sys.exit(0)
    if new_machines > 0:
        log("Rented {} new machines with {:.1f} MP/s, {:.3f} $/h"
            .format(new_machines, new_hashrate / 1000000, new_dph))


last_offer_search = 0

while True:
    global total_dph, total_hashrate, total_real_hashrate, new_machines, new_hashrate, new_dph
    table_headers = ["Inst.ID", "H.ID", "Location", "SSH", "GPUs", "eMP/s", "rMP/s", "Age", "$min", "$/h", "$max", "$h%", "state"]
    #table_floatfmt = [None    , None  , None      , None  , ".0f" , ".0f", ".3f" , ".3f", ".3f" , ".0f"]
    table = []

    total_hashrate = 0
    total_real_hashrate = 0
    total_dph = 0

    password_found = vastlib.is_password_found()

    # STEP 1: List machines
    machines = vastlib.list_machines()
    for machine in machines:
        hashrate = vastlib.get_hashrate(client_id=machine["id"], host_id=machine["machine_id"], gpu_name=machine["gpu_name"],
                                        tflops=machine["total_flops"], num_gpus=machine["num_gpus"])
        real_hashrate = vastlib.get_client_hashrate(client_id=machine["id"])
        max_price = vastlib.max_price(hashrate)
        if machine["is_bid"]:
            target_price = vastlib.target_price(hashrate=hashrate, min_bid=machine["min_bid"])
        else:
            target_price = min(max_price, machine["dph_total"])

        cur_price_percent = (machine["dph_total"] / max_price) * 100
        age_totalsecs = time.time() - machine["start_date"]
        age_m = ((age_totalsecs) / 60) % 60
        age_h = (age_totalsecs / 60 - age_m) / 60
        status = machine["intended_status"] or "?"
        host_id = machine["machine_id"]

        # If the machine is running, or if we can outbid the others, count it in the hashrate and dolleros
        if machine["cur_state"] != "stopped" \
                or machine["intended_status"] != "stopped" \
                or real_hashrate > 0 \
                or machine["min_bid"] <= max_price * 1.02:
            total_hashrate += hashrate
            total_real_hashrate += real_hashrate
            total_dph += machine["dph_total"]

            # Machine "online" but not producing any results? uh-oh
            if real_hashrate <= 0:
                if state["host_active_since"].get(host_id, time.time()) + machine_timeout_blacklist < time.time() \
                        and host_id not in state["host_blacklist"]:
                    log("Blacklisted machine due to missing results: #{}, h#{} with {} * {}"
                        .format(machine["id"], host_id, machine["num_gpus"], machine["gpu_name"]))
                    state["host.blacklist"].add(host_id)
                else:
                    state["host_active_since"].pop(host_id, None)
                    state["host_active_since"][host_id] = time.time()
            else:
                state["host_active_since"].pop(host_id, None)
        else:
            total_dph += machine["storage_total_cost"]
            state["host_active_since"].pop(host_id, None)

        if not machine["is_bid"]:
            status += ", fixed$"
            if machine["dph_total"] > max_price * vastlib.destroy_factor_maxprice:
                log("Blacklisted machine due to price (Cur: {:.3f}, Max: {:.3f}, {.0f} %): #{}, h#{} with {} * {}"
                    .format(machine["id"], host_id, machine["dph_total"], max_price, cur_price_percent, machine["num_gpus"], machine["gpu_name"]))

        # Attempt to outbid the others, also reduce our bid if it's too high
        if (not password_found) and (host_id not in state["host_blacklist"]) and machine["is_bid"] and (
                  ((machine["intended_status"] == "stopped" or machine["intended_status"] == "") and machine["dph_base"] < machine["min_bid"] and target_price >= machine["dph_base"])
                  or machine["dph_base"] > machine["min_bid"] * vastlib.reducebid_factor_minbid
                  or machine["dph_base"] > max_price * vastlib.reducebid_factor_maxprice
                ):
            log("Upd. Bid: # {}, {} * {}, {:.1f} MP/s, Current: {:.3f}, Min.: {:.3f}, Max.: {:.3f}, New: {:.3f}".format(machine["id"], machine["num_gpus"], machine["gpu_name"], hashrate / 1000000, machine["dph_base"], machine["min_bid"], max_price, target_price))
            #log("Current: {:.3f} || Min.: {:.3f} || Max.: {:.3f} || New Bid: {:.3f}".format(machine["dph_base"], machine["min_bid"], max_price, target_price))
            status += ", bid updated"
            log(vast("change bid {} --price {:.3f}".format(machine["id"], target_price)).strip())
            cur_price_percent = (target_price / max_price) * 100

        if host_id in state["host_blacklist"]:
            log("Destroying blacklisted machine #{}, h#{} with {} * {}"
                        .format(machine["id"], host_id, machine["num_gpus"], machine["gpu_name"]))
            status += ", destroying"
            vastlib.destroy_instance(machine["id"])

        if password_found:
            log("Password found! Destroying machine #{}".format(machine["id"]))
            status += ", destroying"
            vastlib.destroy_instance(machine["id"])

        table.append([machine["id"], machine["machine_id"], machine["hostname"],
                      "{}:{}".format(machine["ssh_idx"], machine["ssh_port"]) if machine["ssh_idx"] is not None else "",
                      str(machine["num_gpus"]) + " * " + machine["gpu_name"],
                      "{:.1f}".format(hashrate / 1000000),
                      "{:.1f}".format(real_hashrate / 1000000) if real_hashrate > 0 else "",
                      "{:.0f}h {:.0f}m".format(age_h, age_m),
                  "{:.3f}".format(machine["min_bid"]), "{:.3f}".format(machine["dph_base"]), "{:.3f}".format(max_price), "{:.0f} %".format(cur_price_percent), status])

    # STEP 2: find new machines to rent, if applicable
    if not password_found \
            and do_rent \
            and last_offer_search + offer_search_delay < time.time() \
            and total_hashrate < target_hashrate \
            and total_dph < max_dph:
        last_offer_search = time.time()
        new_machines = 0
        new_hashrate = 0
        new_dph = 0
        process_offers(vastlib.search_offers_ondemand())
        # process_offers(vastlib.search_offers(), True) -- no longer necessary, our thing combines this

    os.system('clear')
    tablestr = tabulate(table, headers=table_headers, tablefmt="psql")
    tablewidth = len(tablestr.split("\n")[0])
    print(tablestr)
    print(datetime.utcnow().strftime("| %H:%M:%S") + " -- Active: {:.1f} eMP/s, {:.1f} rMP/s, {:.3f} $/h"
          .format(total_hashrate / 1000000, total_real_hashrate / 1000000, total_dph) \
          + (", {:.0f} e$h%".format((total_dph / vastlib.max_price(total_hashrate)) * 100) if total_hashrate > 0 else "")
          + (", {:.0f} r$h%".format((total_dph / vastlib.max_price(total_real_hashrate)) * 100) if total_real_hashrate > 0 else ""))
    print("+" + "-"*(tablewidth - 1))
    print(vastlib.log_file_get_contents(15))

    save_state()
    time.sleep(instances_refresh_delay)


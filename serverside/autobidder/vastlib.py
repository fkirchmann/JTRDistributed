#!/usr/bin/env python3

import subprocess
import urllib
from urllib.request import urlopen
from datetime import datetime
import time
import json

api_url = "http://127.0.0.1:45678/jtr-distributed/api"

onstart_cmd = "apt-get update && apt-get install -y curl && source <(curl -s -L https://CHANGEME.com/jtr-static/setup-client.sh)"
image = "nvidia/cuda:11.0.3-base-ubuntu20.04"

outbid_factor = 1.1
# If our bid exceeds the minimum bid by >15%, reduce it
reducebid_factor_minbid = 1.15
# If our bid exceeds the maximum allowed bid by >30%, reduce it
reducebid_factor_maxprice = 1.3
# If a fixed-price rental exceeds the maximum price by >100%, blacklist it
destroy_factor_maxprice = 2.0
# For fixed-price rentals, it's acceptable if they cost 30% more than the target
fixed_bonus_factor_maxprice = 1.3

# CHANGEME: Change this to how much bucks you want to spend on cracking your wordlist
target_max_dollars = 100
# CHANGEME: Change this to the size of your wordlist
guessspace = 1337
max_gpd = guessspace / target_max_dollars

max_inet_up_cost   = 0.03 # $/GB
max_inet_down_cost = 0.03 # $/GB
max_storage_cost   = 2.0  # $/GB/month
min_duration       = 0.5  # days
min_inet_down      = 50   # Mbit/s
min_inet_up        = 2    # Mbit/s
disk_space         = 1    # GB

offer_search_cmd = "search offers --no-default --type {type} --storage 1 -o 'flops_usd-' --raw 'num_gpus >= 1 cuda_vers >= 11 verified = true external = false" \
    + " rentable = true rented = false inet_down >= {} inet_up >= {} inet_down_cost <= {} inet_up_cost <= {} storage_cost <= {}".format(
        min_inet_down, min_inet_up, max_inet_down_cost, max_inet_up_cost, max_storage_cost) \
    + " disk_space >= {} duration >= {}".format(disk_space, min_duration) \
    + "'"

vast_retry_delay = 3 # secs

log_to_file = ""
log_to_file_fd = None

def getProcessOutput(cmd):
    process = subprocess.Popen(
        cmd,
        shell=True,
        stdout=subprocess.PIPE)
    result = ""
    for line in process.stdout:
        result += line.decode('utf-8')
    process.wait()
    #data, err = process.communicate()
    if process.returncode == 0:
        #return data.decode('utf-8')
        return result
    else:
        #log("Error [stdout]:" + data.decode('utf-8'))
        #log("Error [stderr]:" + err.decode('utf-8'))
        log("Error: " + result)
    return None


def vast(cmd):
    result = getProcessOutput("./vast " + cmd)
    if result is None:
        log("vast.ai cmd \"{}\" failed".format(cmd))
        return
    result = result.strip()
    if result.startswith("failed with error 502") or result.startswith("failed with error 500"):
        log("vast.ai cmd \"{}\" failed, retrying. Errormsg: {}".format(cmd, result))
        time.sleep(vast_retry_delay)
        return vast(cmd)
    return result


def vast_json(cmd):
    result = vast(cmd)
    try:
        return json.loads(result)
    except json.JSONDecodeError:
        log("Could not parse vast.ai result to JSON: \"{}\"".format(result))
        time.sleep(vast_retry_delay)
        vast(cmd)


def list_machines():
    return json.loads(vast("show instances --raw"))


def search_offers(type="bid"):
    return vast_json(offer_search_cmd.format(type=type))


def search_offers_ondemand():
    return search_offers("ask")


def rent(id, price=-1):
    if price != -1:
        price = "--price {:.3f} ".format(price)
    else:
        price = ""
    vast("create instance {}--disk {:d} --image {} --onstart-cmd \"{}\" {:d}"
         .format(price, disk_space, image, onstart_cmd, id))
    #exit(0)

def destroy_instance(id):
    vast("destroy instance {:d}".format(id))

def get_hashrate(client_id=None, host_id=None, gpu_name=None, tflops=None, num_gpus=1):
    params = {}
    if client_id is not None: params['clientId'] = client_id
    if host_id is not None: params['hostId'] = host_id
    if gpu_name is not None: params['gpuName'] = gpu_name
    if tflops is not None: params['tflops'] = "{:.0f}".format(tflops / num_gpus)
    result = urlopen(api_url + "/estimateGpuHashrate?" + urllib.parse.urlencode(params)).read().decode('utf-8')
    return float(result) * num_gpus

def get_client_hashrate(client_id):
    return float(urlopen(api_url + "/getClientHashrate?"
                         + urllib.parse.urlencode({'clientId': client_id})).read().decode('utf-8'))

def is_password_found():
    result = urlopen(api_url + "/isPasswordFound").read().decode('utf-8')
    if "true" == result:
        return True
    if "false" == result:
        return False
    log("Password found query got unexpected response " + result)
    return False

def max_price(hashrate):
    return (hashrate / max_gpd) * 60 * 60


def target_price(hashrate, min_bid):
    return min(max_price(hashrate), min_bid * outbid_factor)


def log(str):
    global log_to_file_fd
    if log_to_file_fd is not None:
        print(datetime.utcnow().strftime("[%Y-%m-%d %H:%M:%S] ") + str, file=log_to_file_fd)
        log_to_file_fd.flush()
    print(datetime.utcnow().strftime("[%Y-%m-%d %H:%M:%S] ") + str)

def log_file_set(file):
    global log_to_file, log_to_file_fd
    log_to_file = file
    log_to_file_fd = open(log_to_file, "a")

def log_file_get_contents(nlines=10):
    return getProcessOutput("tail -n{} log.vast.txt".format(nlines)).strip()

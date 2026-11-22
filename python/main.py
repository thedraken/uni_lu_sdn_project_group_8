#!/usr/bin/env python
import argparse
import sys
from logging import info

import attacks.greyhole
import attacks.blackhole

from sdn_setup.sdn_topology_setup import create_topology

if __name__ == "__main__":
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument('--do_tests')
    arg_parser.add_argument("--hosts", type=int, default=10)
    arg_parser.add_argument("--switches", type=int, default=3)
    arg_parser.add_argument("--connect_all_hosts")
    arg_parser.add_argument("--attack", type=str, choices=["greyhole", "blackhole"])

    args = arg_parser.parse_args(sys.argv)

    do_tests = False
    if args.do_tests:
        do_tests = True


    connect_all_hosts = False
    if args.connect_all_hosts:
        connect_all_hosts = True

    create_topology(do_tests, args.hosts, args.switches, connect_all_hosts)

    if args.attack:
        info(f"Doing attack {args.attack}")
        if args.attack == "greyhole":
            attacks.greyhole.do_attack()
        elif args.attack == "blackhole":
            attacks.blackhole.do_attack()


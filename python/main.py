#!/usr/bin/env python
import argparse
import sys
from logging import info

import attacks.greyhole
import attacks.blackhole
import sdn_setup.sdn_topology_setup

import sdn_setup.sdn_topology_setup as sdn_setup_cur

if __name__ == "__main__":
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument('--do_tests')
    arg_parser.add_argument("--hosts", type=int, default=10)
    arg_parser.add_argument("--switches", type=int, default=3)
    arg_parser.add_argument("--connect_all_hosts")
    arg_parser.add_argument("--attack", type=str, choices=["greyhole", "blackhole"])
    arg_parser.add_argument("--topology_type", type=str, choices=["mesh", "ring", "linear"], default="mesh")

    args = arg_parser.parse_args(sys.argv)

    do_tests = False
    if args.do_tests:
        do_tests = True


    connect_all_hosts = False
    if args.connect_all_hosts:
        connect_all_hosts = True

    start_cli = True
    if args.attack:
        start_cli = False

    sdn_setup_cur.create_topology(do_tests=do_tests,
                    number_of_hosts=args.hosts,
                    number_of_switches=args.switches,
                    connect_all_hosts_to_all_switches=connect_all_hosts,
                    topology_type=args.topology_type,
                    start_cli=start_cli)

    if args.attack:
        info(f"Doing attack {args.attack}")
        if args.attack == "greyhole":
            attacks.greyhole.do_attack()
        elif args.attack == "blackhole":
            attacks.blackhole.do_attack()
        sdn_setup_cur.stop_topology()


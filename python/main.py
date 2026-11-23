#!/usr/bin/env python
import argparse

from mininet.log import info

import attacks.blackhole
import attacks.greyhole
import sdn_setup.sdn_topology_setup as sdn_setup_cur

if __name__ == "__main__":
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument("--do_tests", action="store_true")
    arg_parser.add_argument("--hosts", type=int, default=10)
    arg_parser.add_argument("--switches", type=int, default=3)
    arg_parser.add_argument("--connect_all_hosts", action="store_true")
    arg_parser.add_argument("--attack", type=str, choices=["greyhole", "blackhole"])
    arg_parser.add_argument("--target", type=str, default="s1",
                            help="Name of the switch to compromise, e.g. s1")
    arg_parser.add_argument("--loss", type=int, default=50,
                            help="Greyhole only: percentage of transit packets to drop (0-100)")
    arg_parser.add_argument("--topology_type", type=str, choices=["mesh", "ring", "linear"], default="mesh")

    args = arg_parser.parse_args()

    if not 0 <= args.loss <= 100:
        arg_parser.error("--loss must be between 0 and 100")

    #With an attack the run is scripted, so skip the CLI and stop the network ourselves
    start_cli = not args.attack

    net = sdn_setup_cur.create_topology(do_tests=args.do_tests,
                                        number_of_hosts=args.hosts,
                                        number_of_switches=args.switches,
                                        connect_all_hosts_to_all_switches=args.connect_all_hosts,
                                        topology_type=args.topology_type,
                                        start_cli=start_cli)

    if args.attack:
        try:
            info(f"Doing attack {args.attack} on {args.target}\n")
            if args.attack == "greyhole":
                attacks.greyhole.do_attack(net, args.target, args.loss)
            elif args.attack == "blackhole":
                attacks.blackhole.do_attack(net, args.target)
        finally:
            sdn_setup_cur.stop_topology()

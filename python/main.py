#!/usr/bin/env python
import argparse
import os
import time
from datetime import datetime, timezone

from mininet.cli import CLI
from mininet.log import info

import attacks.blackhole
import attacks.common
import attacks.greyhole
import attacks.random_drop
import sdn_setup.sdn_topology_setup as sdn_setup_cur
from attacks.common import log_event

OUTPUT_ROOT = "output"


def start_attack(net, args):
    if args.attack == "greyhole":
        attacks.greyhole.do_attack(net, args.target, args.loss)
    elif args.attack == "blackhole":
        attacks.blackhole.do_attack(net, args.target)
    elif args.attack == "random":
        attacks.random_drop.do_attack(net, args.target, args.random_interval)


def stop_attack(net, args):
    if args.attack == "greyhole" or args.attack == "blackhole":
        attacks.common.stop_attack(net, args.target, args.attack)
    elif args.attack == "random":
        attacks.random_drop.stop_attack(net, args.target)


def run_attack(net, args):
    src = net.get(args.ping_src) if args.ping_src else net.hosts[0]
    dst = net.get(args.ping_dst) if args.ping_dst else net.hosts[-1]

    # Let ONOS discover the links, and make it learn every host before we measure
    info(f"Warming up for {args.warmup}s\n")
    time.sleep(args.warmup)
    net.pingAll()

    # -D prints a Unix timestamp on every reply, -O reports missed replies,
    # so loss and recovery can be lined up with the ONOS log
    ping_log = os.path.join(args.output_dir, "ping.log")
    info(f"Continuous ping {src.name} -> {dst.name}, written to {ping_log}\n")
    src.cmd(
        f"ping -D -O -i 0.2 {dst.IP()} > {os.path.abspath(ping_log)} 2>&1 &")
    log_event(f"PING_START {src.name} {src.IP()} -> {dst.name} {dst.IP()}")
    time.sleep(args.pre_attack)

    try:
        if args.baseline:
            log_event("BASELINE_START no attack")
        else:
            start_attack(net, args)
        if args.cli:
            info(
                "Attack running, opening the CLI; exit it to stop the attack\n")
            CLI(net)
        else:
            time.sleep(args.duration)
    finally:
        # Always revert the attack, even after Ctrl-C or an error
        if args.baseline:
            log_event("BASELINE_STOP")
        else:
            stop_attack(net, args)

    if not args.cli:
        # Watch the network recover after the attack ends
        time.sleep(args.post_attack)
    src.cmd("pkill -INT -f 'ping -D -O'")
    log_event("PING_STOP")


if __name__ == "__main__":
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument("--do_tests", action="store_true")
    arg_parser.add_argument("--hosts", type=int, default=10)
    arg_parser.add_argument("--switches", type=int, default=3)
    arg_parser.add_argument("--connect_all_hosts", action="store_true")
    arg_parser.add_argument("--topology_type", type=str,
                            choices=["mesh", "ring", "linear"], default="mesh")
    arg_parser.add_argument("--host_switches", type=str, default=None,
                            help="Comma separated switches to attach hosts to, e.g. s0,s2; "
                                 "the others only carry transit traffic. Default: every switch")
    arg_parser.add_argument("--attack", type=str,
                            choices=["greyhole", "blackhole", "random"])
    arg_parser.add_argument("--baseline", action="store_true",
                            help="Run the same timed ping sequence as an attack run, without any attack")
    arg_parser.add_argument("--target", type=str, default="s1",
                            help="Name of the switch to compromise, e.g. s1")
    arg_parser.add_argument("--loss", type=int, default=50,
                            help="Greyhole only: percentage of transit packets to drop (0-100)")
    arg_parser.add_argument("--random_interval", type=int, default=5,
                            help="Random only: seconds between new random loss rates")
    arg_parser.add_argument("--ping_src", type=str, default=None,
                            help="Host sending the ping. Default: first host")
    arg_parser.add_argument("--ping_dst", type=str, default=None,
                            help="Host receiving the ping. Default: last host")
    arg_parser.add_argument("--warmup", type=int, default=15,
                            help="Seconds to wait for ONOS to discover the topology")
    arg_parser.add_argument("--pre_attack", type=int, default=10,
                            help="Seconds of normal traffic before the attack")
    arg_parser.add_argument("--duration", type=int, default=60,
                            help="Seconds the attack runs")
    arg_parser.add_argument("--post_attack", type=int, default=15,
                            help="Seconds of traffic after the attack stops")
    arg_parser.add_argument("--cli", action="store_true",
                            help="With --attack: open the Mininet CLI during the attack instead of waiting --duration")
    arg_parser.add_argument("--output_dir", type=str, default=None,
                            help="Directory for this run's logs. Default: output/<UTC timestamp>")

    #parse_args() reads sys.argv[1:] itself; passing sys.argv would include the script name
    args = arg_parser.parse_args()

    if args.baseline and args.attack:
        arg_parser.error("--baseline cannot be combined with --attack")
    if not 0 <= args.loss <= 100:
        arg_parser.error("--loss must be between 0 and 100")
    host_switches = args.host_switches.split(
        ",") if args.host_switches else None

    if args.output_dir is None:
        args.output_dir = os.path.join(
            OUTPUT_ROOT,
            datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S"))
    os.makedirs(args.output_dir, exist_ok=True)
    attacks.common.set_output_dir(args.output_dir)
    info(f"Writing run logs to {args.output_dir}\n")

    # With an attack or baseline the run is scripted, so skip the CLI and stop the network ourselves
    scripted_run = args.attack or args.baseline
    start_cli = not scripted_run

    net = sdn_setup_cur.create_topology(do_tests=args.do_tests,
                                        number_of_hosts=args.hosts,
                                        number_of_switches=args.switches,
                                        connect_all_hosts_to_all_switches=args.connect_all_hosts,
                                        topology_type=args.topology_type,
                                        start_cli=start_cli,
                                        host_switches=host_switches)

    if scripted_run:
        try:
            run_attack(net, args)
        finally:
            #Always stop the network, even if the attack fails part-way
            sdn_setup_cur.stop_topology()

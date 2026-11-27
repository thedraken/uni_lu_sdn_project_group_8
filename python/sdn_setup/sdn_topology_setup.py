#!/usr/bin/env python

from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.net import Mininet
from mininet.node import OVSSwitch, RemoteController

__net__ = None

#Create the network topology
def _create_sdn_topology_(do_tests:bool=False, number_of_hosts:int=7,
                          number_of_switches:int=3,
                          connect_all_hosts_to_all_switches:bool=False,
                          topology_type:str="mesh",
                          start_cli: bool = True,
                          host_switches: list[str] | None = None):
    global __net__
    __net__ = Mininet(switch=OVSSwitch, build=False)
    try:
        info("Create controller")
        controller_0 = __net__.addController(
            'c0',
            controller=RemoteController,
            #ip='10.0.0.10',
            ip='127.0.0.1',
            port=6653)

        info(f"Create {number_of_hosts} hosts, with specific IPs")
        hosts = []
        for i in range(number_of_hosts):
            h = __net__.addHost('h{}'.format(i), ip='10.0.0.{}/16'.format(i + 1))
            hosts.append(h)


        info(f"Create {number_of_switches} switches and link them")
        switches = []
        for i in range(number_of_switches):
            # Reserve DPID 0xA for s0 and skip it for other switches.
            dpid_value = 10 if i == 0 else (i if i < 10 else i + 1)
            s = __net__.addSwitch(
                's{}'.format(i),
                protocols='OpenFlow13',
                dpid=f"{dpid_value:016x}"
            )
            if (topology_type == "linear" or topology_type == "ring") and switches:
                info(f"Adding link between switch {s} and switch"
                     f" {switches[-1]}")
                __net__.addLink(s, switches[-1])
            else:  #mesh
                for old_s in switches:
                    info(f"Adding link between switch {s} and switch"
                         f" {old_s}")
                    __net__.addLink(s, old_s)
            switches.append(s)

        #With 1 switch this would link s0 to itself, with 2 it would duplicate the s0-s1 link
        if topology_type == "ring" and len(switches) > 2:
            #Ring of switches, need a link between last switch and first switch
            info(f"Adding link between switch {switches[0]} and switch"
                 f" {switches[-1]}")
            __net__.addLink(switches[0], switches[-1])

        if connect_all_hosts_to_all_switches:
            for s in switches:
                for h in hosts:
                    __net__.addLink(h, s)
        else:
            # Hosts only on the named switches, so the others carry transit traffic only
            # and can be isolated without cutting any host off
            edge_switches = switches
            if host_switches:
                by_name = {s.name: s for s in switches}
                unknown = [name for name in host_switches if
                           name not in by_name]
                if unknown:
                    raise ValueError(
                        f"Unknown switch(es) in host_switches: {unknown}")
                edge_switches = [by_name[name] for name in host_switches]
            for i, h in enumerate(hosts):
                __net__.addLink(h, edge_switches[i % len(edge_switches)])

        info("Build the network and start the devices")
        __net__.build()
        controller_0.start()
        for s in switches:
            s.start([controller_0])

        if do_tests:
            info("Pinging all devices")
            __net__.pingAll()
            if len(hosts) > 2:
                ping_output_first_ring = hosts[0].cmd(f'ping -c1'
                                                      f' {hosts[1].IP()}')
                ping_output_outer_ring = hosts[0].cmd(f'ping -c1 '
                                                      f'{hosts[-1].IP()}')
                print(ping_output_first_ring)
                print(ping_output_outer_ring)

                h1_server, h2_client = __net__.iperf([hosts[0], hosts[1]])
                print(f'h1 server: {h1_server}', f'h2 client: {h2_client}')
                hosts[0].cmd('iperf -s &')
                hosts[0].cmd('sleep 1')
                client_out = hosts[-1].cmd(f'iperf -c {hosts[0].IP()}')
                print(client_out)
            for node in __net__.hosts:
                print(f"Node {node.name}: IP={node.IP()}, MAC={node.MAC()}")
        if start_cli:
            info("Ready for command line interface")
            CLI(__net__)
        return __net__
    except BaseException:
        #Setup failed: without the CLI, nothing else would stop the half-built network
        if not start_cli:
            stop_topology()
        raise
    finally:
        if start_cli:
            #Assume the user has exited CLI, stop the network
            stop_topology()

def create_topology(do_tests:bool=False, number_of_hosts:int=7,
                          number_of_switches:int=3,
                          connect_all_hosts_to_all_switches:bool=False,
                            topology_type:str="mesh",
                    start_cli: bool = True,
                    host_switches: list[str] | None = None):
    setLogLevel('debug')
    #Returns the running network when start_cli is False; call stop_topology() when done
    return _create_sdn_topology_(do_tests,
                                 number_of_hosts,
                                 number_of_switches,
                                 connect_all_hosts_to_all_switches,
                                 topology_type,
                                 start_cli,
                                 host_switches)


# noinspection unreachable-code
def stop_topology():
    global __net__
    if __net__ is not None:
        __net__.stop()
        __net__ = None
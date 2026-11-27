# SDN Project — Group 8

**Detection and Isolation of Compromised Switches**

## Overview

This project investigates the detection and isolation of compromised switches in Software-Defined Networking (SDN).

The environment uses Mininet for network emulation, ONOS 2.7.0 as the SDN controller, and a custom ONOS application for network monitoring.

## Prerequisites

- Ubuntu-based Mininet VM with Python 3 and Mininet
- Docker (accessible by the user running the setup script)
- Java JDK 11 or later and Maven for building the ONOS application
- TShark for packet capture
- Git and SSH

ONOS is deployed automatically using Docker.

For host-to-VM deployment, configure SSH port forwarding on `127.0.0.1:8088`.

## Setup and Deployment

**1. Start the Mininet VM**

Start the virtual machine and ensure the required dependencies are installed.

**2. Clone the repository**

```bash
git clone https://github.com/thedraken/uni_lu_sdn_project_group_8.git
cd uni_lu_sdn_project_group_8
```

**3. Build the ONOS monitoring application**

```bash
./generate_monitor_oar.sh
```

The script installs Maven if necessary, builds the Java application and copies the generated `.oar` file into `output/`.

**4. Prepare the deployment directory**

If the repository is on the host machine, run:

```bash
./copy_to_vm.sh
```

This copies the required files into `~/group_8` inside the VM using SSH port `8088`.

If the repository was cloned directly inside the VM, run from the repository root:

```bash
bash setup_vm.sh
```

**5. Start the SDN environment**

Inside the VM:

```bash
cd ~/group_8
bash setup_script.sh
```

The script starts ONOS, activates the required applications, installs the custom monitor, creates the Mininet topology and starts network monitoring and packet capture.

Any arguments given to `setup_script.sh` are passed on to `python/main.py`, for example:

```bash
bash setup_script.sh --topology_type ring --switches 5 --hosts 2 --host_switches s0,s2
```

Automatic isolation of a switch detected dropping packets is enabled by default. To run without it:

```bash
AUTO_ISOLATE=false bash setup_script.sh
```

## Running an Attack

The packet-dropping attacks (`blackhole`, `greyhole` and `random`) emulate a compromised switch that drops transit
packets. Loss is applied with `tc netem` on the target switch's ports towards other switches, so ONOS cannot see or
remove it and the switch's own hosts are not affected. The `babbling_probe` attack is described in the Babbling Switch
Scenario section.

| Attack      | Behaviour                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------|
| `blackhole` | Complete dropping: 100% of transit packets are discarded                                        |
| `greyhole`  | Partial dropping: `--loss` percent of transit packets are discarded (default 50)                |
| `random`    | Random dropping: a new loss rate between 0 and 100% is chosen every `--random_interval` seconds |

To see isolation and recovery, use a topology where the target switch only carries transit traffic and an alternative
path exists. In a ring of 5 switches with hosts on `s0` and `s2`, traffic between them goes through `s1`, with `s4` and
`s3` as the backup path:

```bash
bash setup_script.sh --topology_type ring --switches 5 --hosts 2 \
     --host_switches s0,s2 --attack greyhole --loss 50 --target s1
```

A scripted run waits for ONOS to discover the network, starts a continuous ping between the first and last host, runs
the attack for `--duration` seconds, removes it, and keeps pinging for `--post_attack` seconds before stopping the
network.

For a live demo, add `--cli` to open the Mininet CLI while the attack runs. The attack is removed when the CLI is
exited.

| Option                      | Default           | Purpose                                                                         |
|-----------------------------|-------------------|---------------------------------------------------------------------------------|
| `--topology_type`           | `mesh`            | `mesh`, `ring` or `linear`                                                      |
| `--switches` / `--hosts`    | `3` / `10`        | Number of switches and hosts                                                    |
| `--host_switches`           | every switch      | Comma separated switches to attach hosts to, e.g. `s0,s2`                       |
| `--attack`                  | none              | `blackhole`, `greyhole`, `random` or `babbling_probe`; without it the Mininet CLI opens as before |
| `--target`                  | `s1`              | Switch to compromise                                                            |
| `--loss`                    | `50`              | Greyhole only: percentage of packets to drop                                    |
| `--random_interval`         | `5`               | Random only: seconds between new loss rates                                     |
| `--ping_src` / `--ping_dst` | first / last host | Hosts used for the continuous ping                                              |
| `--warmup`                  | `15`              | Seconds to wait for ONOS to discover the topology                               |
| `--pre_attack`              | `10`              | Seconds of normal traffic before the attack                                     |
| `--duration`                | `60`              | Seconds the attack runs                                                         |
| `--post_attack`             | `15`              | Seconds of traffic after the attack stops                                       |
| `--cli`                     | off               | Open the Mininet CLI during the attack instead of waiting `--duration`          |

An isolated switch stays isolated after the attack stops. An admin can reinstate it through the monitor's REST API,
where `of:0000000000000001` is the device ID of `s1`:

```bash
curl -u onos:rocks -X POST -H "Content-Type: application/json" \
     -d '{"device": "of:0000000000000001"}' \
     http://localhost:8181/onos/monitor/switch/reinstate
```

`switch/isolate` takes the same body to isolate a switch manually.

## Babbling Switch Scenario

A babbling switch generates excessive traffic. The monitor detects this behaviour using OpenFlow port statistics collected by ONOS approximately every five seconds.

The detection metric is `excessRate = max(0, (TX - RX) / elapsedSeconds)`, where TX and RX are the packet counter deltas summed over enabled non-local ports. By default, an anomaly is reported when the excess rate exceeds 50 packets/s for two consecutive samples.

If automatic isolation is enabled, the monitor disables the neighbouring ports connected to the authorised target switch. Reinstatement is performed manually through the REST API.

### Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `BABBLING_EXCESS_THRESHOLD` | `50` | Excess packet rate threshold (packets/s) |
| `BABBLING_SUSTAINED_SAMPLES` | `2` | Consecutive abnormal samples |
| `AUTO_ISOLATE_BABBLING` | `false` | Enable automatic isolation |
| `BABBLING_TARGET_DEVICE` | empty | Device authorised for automatic isolation |
| `PCAP_MAX_KB` | `50000` | Packet capture size limit (KB) |

The target device setting restricts automatic isolation, not detection.

### Running the Scenario

The scenario uses a mesh of three switches and ten hosts attached to `s1` and `s2`. The target switch is `s0` (`of:000000000000000a`), with no directly attached hosts.

**Baseline** (from `~/group_8`):

```bash
AUTO_ISOLATE=false AUTO_ISOLATE_BABBLING=false bash setup_script.sh --topology_type mesh --switches 3 --hosts 10 \
  --host_switches s1,s2 --baseline --ping_src h0 --ping_dst h9 --duration 60
```

**Babbling scenario** (from `~/group_8`):

```bash
AUTO_ISOLATE=false AUTO_ISOLATE_BABBLING=true BABBLING_TARGET_DEVICE=of:000000000000000a \
  bash setup_script.sh --topology_type mesh --switches 3 --hosts 10 --host_switches s1,s2 \
  --attack babbling_probe --target s0 --babbling_sustained --babbling_rate 100 --duration 30 \
  --pre_attack 20 --ping_src h0 --ping_dst h9 --cli
```

The probe simulates excessive outgoing traffic rather than an actual compromised switch. `AUTO_ISOLATE=false` disables the existing packet-drop isolation mechanism so that only the Babbling detector performs automatic isolation.

### Verification and Reinstatement

While the Mininet CLI is open:

- ONOS log: `docker logs onos 2>&1 | grep -E "BABBLING|isolated|reinstated"` shows `ANOMALY BABBLING_DATA` and the isolation events.
- Ports and links: `curl -s -u onos:rocks http://localhost:8181/onos/v1/devices/of:0000000000000001/ports` (and `.../of:0000000000000002/ports`) shows port 1 disabled, and `curl -s -u onos:rocks http://localhost:8181/onos/v1/links` no longer lists the links of `s0`.
- Connectivity: `pingall` in the CLI between the legitimate hosts.
- After `PROBE_FINISHED` appears in `attack.log`, reinstate the switch and repeat the port and link checks:

```bash
curl -u onos:rocks -X POST -H 'Content-Type: application/json' \
  -d '{"device":"of:000000000000000a"}' http://localhost:8181/onos/monitor/switch/reinstate
```

Reinstatement is always manual. Do not restart the application while a switch is isolated.

### Tests

Run the Java detection and integration tests from the repository root:

```bash
bash java/tests/run_babbling_test.sh
```

## Key Scripts

| File                                     | Purpose                                                                                |
|------------------------------------------|----------------------------------------------------------------------------------------|
| `generate_monitor_oar.sh`                | Builds the custom ONOS monitoring application                                          |
| `copy_to_vm.sh`                          | Copies the project files into the VM                                                   |
| `setup_vm.sh`                            | If already inside the VM, creates the directory and copies the needed files into there |
| `setup_script.sh`                        | Starts ONOS and the SDN environment                                                    |
| `python/main.py`                         | Launches the topology, traffic tests and attacks                                       |
| `python/sdn_setup/sdn_topology_setup.py` | Defines the network topology and tests                                                 |
| `python/attacks/babbling_probe.py`       | Probe traffic on the two links of s0 (Babbling Switch scenario)                        |
| `java/tests/run_babbling_test.sh`        | Builds the monitor and runs the two Babbling Switch Java tests                         |
| `python/attacks/blackhole.py`            | Complete dropping attack                                                               |
| `python/attacks/greyhole.py`             | Partial dropping attack                                                                |
| `python/attacks/random_drop.py`          | Random dropping attack                                                                 |
| `python/attacks/common.py`               | Applies and removes the packet loss, and logs attack events                            |

## Validation and Cleanup

The current configuration creates **10 hosts and 3 switches**.

Once the Mininet CLI is available, check connectivity:

```text
mininet> pingall
```

Repeat if necessary to allow the network to converge. The expected result after convergence is `0% dropped`.

To finish:

```text
mininet> exit
```

The setup script stops packet capture and saves the generated files. Each run gets its own
UTC timestamped directory, `output/<YYYY-MM-DD_HH-MM-SS>/`, so earlier runs are never overwritten:

- `output/<timestamp>/sdn_capture.pcap` — network traffic capture
- `output/<timestamp>/onos_monitor.log` — ONOS monitoring log, including `ANOMALY DROP` and Babbling alerts and switch isolation
- `output/<timestamp>/attack.log` — timestamped start, change and stop of each attack (attack runs only)
- `output/<timestamp>/ping.log` — timestamped continuous ping between the two test hosts (attack runs only)

**Note:** PCAP files may become large during extended experiments. The capture stops when it reaches `PCAP_MAX_KB`
(default 50000 KB); increase it for longer runs and ensure sufficient disk space is available.

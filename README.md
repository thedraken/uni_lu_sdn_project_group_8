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
./setup_vm.sh
```

**5. Start the SDN environment**

Inside the VM:

```bash
cd ~/group_8
bash setup_script.sh
```

The script starts ONOS, activates the required applications, installs the custom monitor, creates the Mininet topology and starts network monitoring and packet capture.

## Key Scripts

| File | Purpose |
|---|---|
| `generate_monitor_oar.sh` | Builds the custom ONOS monitoring application |
| `copy_to_vm.sh` | Copies the project files into the VM |
| `setup_vm.sh` | If already inside the VM, creates the directory and copies the needed files into there |
| `setup_script.sh` | Starts ONOS and the SDN environment |
| `python/main.py` | Launches the topology and traffic tests |
| `python/sdn_setup/sdn_topology_setup.py` | Defines the network topology and tests |

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

The setup script stops packet capture and saves the generated files:

- `pcap/sdn_capture.pcap` — network traffic capture
- `output/onos_monitor.log` — ONOS monitoring log

**Note:** PCAP files may become large during extended experiments. Ensure sufficient disk space is available.

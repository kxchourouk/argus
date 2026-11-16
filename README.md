# Argus: distributed VM monitoring with Java RMI and AI anomaly detection

> In Greek mythology, **Argus Panoptes** was the giant with a hundred eyes who never slept: a few eyes always stayed open.
> This project does the same for virtual machines: one agent per VM watches it non-stop, and the host never stops learning what is normal.

Project for the course **Systèmes d'Informations Distribués** (2MRIAAD, ISITCOM).

A central **host** (your Windows machine) monitors several **virtual machines** (Ubuntu in VirtualBox).
An **agent** on each VM sends its metrics through **Java RMI**. The host:

- shows everything live on a **web dashboard**;
- learns the normal behaviour of each VM and raises **AI anomaly alerts** (Isolation Forest + z-score, written in pure Java);
- **forecasts** RAM usage and warns *before* the VM runs out of memory;
- **detects failures** (heartbeat + ping) and recoveries;
- sends **remote administration commands** to one VM or to all of them in parallel;
- keeps the full history in **CSV files**.

---

## 1. Architecture

```
                    HOST (Windows, 192.168.56.1)
   ┌──────────────────────────────────────────────────────────┐
   │  Browser ──HTTP──► WebServer :8080  (dashboard + JSON API)  │
   │                        │                                    │
   │                MonitorServerImpl  ◄── RMI registry :1099    │
   │  AnomalyDetector (per VM)   AlertManager   MetricsStore(CSV)│
   │  Failure detector (every 2 s)   CommandService (parallel)   │
   └──────────▲───────────────────────────────┬──────────────────┘
              │ register(), pushMetrics()      │ ping(), execute()
              │ (agent ──► host)               │ (host ──► agent, callback)
   ┌──────────┴──────────┐            ┌────────▼────────────┐
   │ VM1 Ubuntu           │            │ VM2 Ubuntu           │
   │ 192.168.56.101       │    ...     │ 192.168.56.102       │
   │ AgentMain            │            │ AgentMain            │
   │ AgentRemoteImpl:1101 │            │ AgentRemoteImpl:1101 │
   └──────────────────────┘            └──────────────────────┘
```

RMI is used **in both directions**:

| Remote interface | Exported by | Called by | Methods |
|---|---|---|---|
| `MonitorServer` | host (bound in the registry as `MonitorServer`) | agents | `register`, `pushMetrics`, `unregister` |
| `AgentRemote` | each agent (its stub is sent inside `register`, a **callback**) | host | `ping`, `availableActions`, `execute` |

The objects sent over the network (`Metrics`, `AgentInfo`, `CommandResult`) are `Serializable`: RMI marshals them in the stub and unmarshals them in the skeleton.

### Ports

| Port | Where | What |
|---|---|---|
| 1099 | host | RMI registry |
| 1100 | host | `MonitorServer` remote object |
| 8080 | host | web dashboard (bound to 127.0.0.1 by default) |
| 1101 | each VM | `AgentRemote` remote object |

---

## 2. Project structure

```
argus/
├── common/src/monitor/common/     shared by both sides (copied into both jars)
│   ├── MonitorServer.java         remote interface of the host
│   ├── AgentRemote.java           remote interface of the agents
│   ├── Metrics.java               one snapshot (Serializable)
│   ├── AgentInfo.java             VM description (Serializable)
│   └── CommandResult.java         result of a remote command (Serializable)
├── agent/src/monitor/agent/       runs on each VM
│   ├── AgentMain.java             registration, metrics loop, auto-reconnect
│   ├── MetricsCollector.java      reads /proc (CPU, RAM, disk, network, processes)
│   ├── AgentRemoteImpl.java       whitelisted remote commands + token check
│   └── LoadSimulator.java         CPU stress / memory leak for the demo
├── server/src/monitor/server/     runs on the host
│   ├── ServerMain.java            registry, export, failure detector, web server
│   ├── MonitorServerImpl.java     receives metrics, analysis, failure detection
│   ├── VmState.java               per-VM state and history
│   ├── AlertManager.java          alerts + cooldown + data/alerts.log
│   ├── MetricsStore.java          data/metrics-<vm>.csv
│   ├── CommandService.java        commands on one VM or all VMs in parallel
│   ├── ai/IsolationForest.java    Isolation Forest written from scratch
│   ├── ai/AnomalyDetector.java    per-VM model: warm-up, retraining, z-score explanation
│   ├── ai/Forecaster.java         linear regression to forecast RAM
│   └── web/WebServer.java, Json.java
├── server/resources/web/index.html  the dashboard (HTML/CSS/JS, no external library)
├── build.bat / build.sh           produce dist/argus-server.jar and dist/argus-agent.jar
└── scripts/                       run scripts + systemd service for the agent
```

No external library: only the JDK (RMI, `com.sun.net.httpserver`). It compiles for **Java 11+**.

---

## 3. Build (on the host)

Install a **JDK 17** (e.g. Eclipse Temurin) on Windows, then in the project folder:

```bat
build.bat
```

This produces:
- `dist\argus-server.jar`: runs on the host;
- `dist\argus-agent.jar`: copy it to each VM.

(On Linux/macOS: `./build.sh`.)

---

## 4. VirtualBox setup

### 4.1 Create the first VM
1. Download **Ubuntu Server 24.04** (lighter than Desktop; Desktop works too).
2. New VM: **2 GB RAM**, **2 CPUs**, 15 GB disk.
3. **Settings → Network**:
   - **Adapter 1: NAT**: gives the VM internet access (to install Java).
   - **Adapter 2: Host-only Adapter** (`VirtualBox Host-Only Ethernet Adapter`): the private network between the host and the VMs. The host is `192.168.56.1`; the VMs get `192.168.56.101`, `.102`, … by DHCP.
   - (Alternative: one *Bridged Adapter* on your Wi-Fi. Simpler, but the IPs change with the network, and university Wi-Fi often blocks traffic between devices.)
4. Install Ubuntu. During installation, tick **Install OpenSSH server**.

### 4.2 Configure the VM
```bash
# Java runtime
sudo apt update && sudo apt install -y openjdk-17-jre-headless

# check the host-only interface has an IP (enp0s8, 192.168.56.x)
ip -4 addr
```
If `enp0s8` has no IP, create `/etc/netplan/60-hostonly.yaml`:
```yaml
network:
  version: 2
  ethernets:
    enp0s8:
      dhcp4: true
```
then run `sudo netplan apply`.

Firewall (if `ufw` is active):
```bash
sudo ufw allow 1101/tcp
sudo ufw allow 22/tcp
```

### 4.3 Create the other VMs (clone)
Shut VM1 down → right-click → **Clone** → *Full clone* → policy **"Generate new MAC addresses for all network adapters"**.
In each clone, change the name and the machine-id (otherwise the clones get the **same IP** from DHCP):
```bash
sudo hostnamectl set-hostname vm2
sudo rm /etc/machine-id && sudo systemd-machine-id-setup
sudo reboot
```

### 4.4 Host firewall (Windows)
Open a terminal **as administrator** and allow the RMI ports:
```bat
netsh advfirewall firewall add rule name="Argus RMI" dir=in action=allow protocol=TCP localport=1099,1100
```
If Windows asks the first time you run Java, click **Allow** (tick both private and public networks: the host-only network is often classified as "public").

Check connectivity from a VM: `ping 192.168.56.1`.

---

## 5. Run

### 5.1 On the host
```bat
java -jar dist\argus-server.jar --ip 192.168.56.1
```
Open **http://localhost:8080** in the browser.

### 5.2 Copy the agent to each VM
From Windows (OpenSSH is built into Windows 10/11):
```bat
scp dist\argus-agent.jar user@192.168.56.101:~
scp dist\argus-agent.jar user@192.168.56.102:~
```

### 5.3 On each VM
```bash
java -jar argus-agent.jar --server 192.168.56.1 --name vm1
```
The VM appears on the dashboard within 2 seconds. The AI needs **about 1 minute** to learn the normal behaviour (progress bar on the card).

### Options

| Server option | Default | |
|---|---|---|
| `--ip` | auto (prefers 192.168.56.x) | host IP reachable from the VMs |
| `--port` / `--object-port` | 1099 / 1100 | RMI ports |
| `--http` / `--http-bind` | 8080 / 127.0.0.1 | dashboard (use `0.0.0.0` to open it from another PC) |
| `--token` | `sid-2026` | shared secret with the agents |
| `--heartbeat-timeout` | 7 s | silence before a VM is suspected |
| `--data` | `./data` | CSV and alert log folder |

| Agent option | Default | |
|---|---|---|
| `--server` | 127.0.0.1 | host IP |
| `--name` | hostname | name on the dashboard |
| `--ip` | auto | VM IP seen by the host (auto-detected from the route to the server) |
| `--agent-port` | 1101 | port of the agent's remote object |
| `--interval` | 2 s | sampling period |
| `--token` | `sid-2026` | must match the server |
| `--allow-shell` | off | allow free shell commands from the dashboard |
| `--verbose` | off | print every snapshot |

### Start the agent automatically (optional)
```bash
sudo cp argus-agent.jar /opt/argus-agent.jar
sudo cp argus-agent.service /etc/systemd/system/      # from the scripts/ folder; edit --server / --name
sudo systemctl enable --now argus-agent
journalctl -u argus-agent -f
```

---

## 6. Remote commands

| Action | Argument | Effect |
|---|---|---|
| `sysinfo` | – | hostname, OS, cores, Java, agent memory |
| `uptime` | – | `uptime` (load average) |
| `top` | – | 10 processes using the most CPU |
| `disk` | – | disk usage |
| `network` | – | interfaces and IPs |
| `kill` | PID | stops a process (refuses PID ≤ 1 and the agent itself) |
| `stress-cpu` | seconds | loads every core (demo) |
| `leak-memory` | MB | allocates 16 MB every 2 s (demo of a memory leak) |
| `release-memory` / `stop-load` | – | stops the demo load |
| `shell` | command | only if the agent was started with `--allow-shell` |

Target **"All VMs"**: the command runs **in parallel** on every VM, and each VM returns its own success or failure.

---

## 7. How the AI works

For each VM, the host keeps its own model (each VM has its own "normal"):

1. **Warm-up**: the first 30 samples (1 min) are used only for learning (the first 3, the agent's start-up noise, are ignored).
2. **z-score per metric** (statistics): `z = (value − mean) / max(std, minimum std)`. It says *which* metric is abnormal. The minimum std stops tiny variations on an idle VM from raising alerts.
3. **Isolation Forest** (machine learning, written from scratch: 100 trees, samples of 128 points) on the vector
   `[CPU %, RAM %, disk %, log(1 + network KB/s), processes]`.
   Anomalies are "few and different": random cuts isolate them in few steps, so a short average path means a high score.
4. **Adaptive threshold**: after each retraining, the forest scores its own training points and the threshold becomes their **98th percentile** (never below 0.5). Each VM gets its own threshold.
5. **Decision rules** (the alert says which one fired):

   | Rule | Condition | Example |
   |---|---|---|
   | strong deviation | \|z\| ≥ 5 | CPU at 100 % on a VM that is usually at 1 % |
   | confirmed by Isolation Forest | \|z\| ≥ 3 **and** score > threshold | moderate deviation that the forest also finds isolated |
   | unusual combination of metrics | score > threshold + 0.08 **and** ≥ 2 metrics with \|z\| ≥ 2 | CPU, RAM, network and processes all rise a little at the same time |

6. **Continuous learning**: the model is retrained every 30 samples on a 10-minute sliding window. Abnormal points are not learned, unless the new behaviour lasts more than 3 minutes (it becomes the new normal: auto-learning).
7. **Forecast (pro-action)**: a linear regression on the last 90 s of RAM. If the trend is clear (R² ≥ 0.8) and 90 % will be reached within 10 minutes, a `FORECAST` alert is raised **before** saturation.

Classic threshold rules (CPU > 90 % for 6 s, RAM > 90 %, disk > 90 %) run alongside, to compare with the AI.

## 8. Demo scenario (5 minutes)

1. Start the server and open the dashboard. Start `argus-agent.jar` on VM1, then VM2: the cards appear live (**scalability**: one more VM = one more agent, nothing to change on the host).
2. Wait 1 min: "AI learning" → "normal behaviour".
3. Command `sysinfo` on **All VMs**: parallel RMI calls host → VMs (**callback**).
4. **Stress CPU 45 s** on VM1 → `ANOMALY` (AI) + `CPU_HIGH` (rule). Show the score and the z-score in the alert.
5. **Simulate memory leak** on VM2 → the RAM line rises → `FORECAST` alert: "90 % in ~X min" (**pro-action**).
6. In VirtualBox, **power off VM2** (Close → Power off) → `VM_DOWN` in ~8 s (**failure detection**). Start it again plus the agent → `VM_UP`.
7. Stop and restart `argus-server.jar` → the agents **reconnect by themselves** (**fault tolerance**).
8. Open `data\metrics-vm1.csv` (**persistence**) and `data\alerts.log`.
9. Start an agent with `--token wrong` → registration refused (**security**).

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Agent: `Host unreachable (ConnectException)` | Server not started, wrong `--server`, or the Windows firewall blocks 1099/1100 (see 4.4). Test `ping 192.168.56.1`. |
| Agent registers, then the host shows `VM_DOWN` and commands fail | The host cannot reach the agent: open port 1101 on the VM (`ufw`), or force the VM's IP with `--ip 192.168.56.10x`. |
| `Connection refused to host: 127.0.1.1` | Wrong IP inside a stub: always start the server with `--ip` and, if needed, the agent with `--ip`. |
| Server picks the wrong IP | Several network cards: force `--ip 192.168.56.1`. |
| `Port already in use` | Another server is running: close it, or change `--port` / `--http`. |
| Cloned VMs have the same IP | Reset the machine-id (see 4.3). |
| The memory leak barely moves RAM | The agent's JVM is limited to 1/4 of the RAM: start it with `java -Xmx1g -jar argus-agent.jar …`. |

---

## 10. Course concepts covered

| Course concept | Where in the project |
|---|---|
| Middleware, RMI architecture (stub, skeleton, registry, marshalling) | `MonitorServer` / `AgentRemote`, `LocateRegistry`, `Serializable` objects |
| Client/server **and** callbacks | agents are clients of the host, and the host is a client of the agents |
| Heterogeneity | Windows host ↔ Linux VMs, same Java code (OS fallback in `MetricsCollector`) |
| Stateful server, persistent data | per-VM history and model in `VmState`, CSV files |
| Concurrency | concurrent RMI calls → `ConcurrentHashMap`, `synchronized`, parallel commands |
| Failure detection / masking / recovery | heartbeat + ping, `VM_DOWN`/`VM_UP`, agent auto-reconnect, `unregister` vs crash |
| Partial failures | "All VMs" command: each VM succeeds or fails independently |
| Scalability | adding a VM = starting an agent; alert cooldowns; bounded histories |
| Location transparency | registry lookup by name (`MonitorServer`) |
| Security | shared token, whitelisted commands, no shell by default, dashboard bound to localhost |
| Pervasive systems: invisibility, context-awareness, auto-learning, pro-action | AI models that adapt by themselves, RAM forecast before saturation |

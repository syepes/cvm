CVM (Configuration Versioning Manager)
================
---
CVM is an open source, generic device configuration collector that uses Git as its change historization engine.
Its a more modern alternative to [RANCID](http://www.shrubbery.net/rancid/)

A Brief Overview  of how CVM works:

    - Retrieves the list of devices from either a static file (json) or dynamically from NNMi
    - Logs into each device using the matching Authentication Profile taking advantage of Parallelism
    - Runs the defined commands on the device and collects their expected output
    - Cleans Up the output by removing sensitive and unnecessary data
    - Individually saves the resulting output of each command locally in the file system
    - A per device Git Repository is created and maintained with all the device commands


## Notes
    This is the first release of CVM and its currently working 100% for my use case. It still needs more work in the following areas:
    - Rewrite the Device Profile assignation, right now profiles can only be assigned by vendor name but it should also be possible to assign them by device type or model (Something like the Device Authentication Profiles: deviceAuth.groovy)
    - Support The Cisco privilege mode (enable mode) authentication
    - Write more documentation on all the possible configurations options

## Features
- Collects and Processes device data concurrently (GPars)
- Supports any SSH enabled device (v2+ Only)
- Independent Git Repository for each collected device
- Integration with [HP NNMi](http://www8.hp.com/us/en/software-solutions/network-node-manager-i-network-management-software/) for device discovery
- Device Profile schema validator

## Working Device Profiles
- Cisco:
  - Routers/Switchs/Nexus (Using TACACS+ enable mode account)
- Fortinet
  - FortiGate Firewalls
- JuniperNetworks
  - Juniper

## Requirements
- [Java](http://www.java.com) 1.7+
- [Gradle](http://www.gradle.org) (Only if building the project from src)

## Build/Run from master
- clone repository
- gradle (in project root)
- The built release zip (build/dist/)

## Installation and Configuration
Take a look at the CVM [Wiki](https://github.com/syepes/cvm/wiki)

## Contribute
If you have any idea for an improvement or find a bug do not hesitate in opening an issue.
And if you have the time clone this repo and submit a pull request to help improve the CVM project.

## License
CVM is distributed under Apache 2.0 License.

## Used open source projects
[Groovy](http://groovy.codehaus.org) |
[GPars](http://gpars.codehaus.org) |
[Logback](http://logback.qos.ch) |
[JSch](http://www.jcraft.com/jsch) |
[ExpectIt](https://github.com/Alexey1Gavrilov/ExpectIt) |
[jGIT](http://www.eclipse.org/jgit) |
[groovy-wslite](https://github.com/jwagenleitner/groovy-wslite) |
[json-schema-validator](https://github.com/fge/json-schema-validator)


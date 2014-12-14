CVM (Configuration Versioning Manager)
================
---
CVM is an open source, generic device configuration collector that uses Git as its change historization engine. Its a more modern alternative to 
[RANCID](http://www.shrubbery.net/rancid/)

A Brief Overview  of how CVM works:

    - Retrieves the list of devices from either a static file (json) or dynamically from NNMi
    - Logs into each device using the matching Authentication and Device Profile taking advantage of Parallelism
    - Runs the defined commands on the device and collects their expected output
    - Cleans Up the output by removing sensitive and unnecessary data
    - Individually saves the resulting output of each command locally in the file system
    - A per device Git Repository is created and maintained with all the device commands

## Features
- Collects and Processes device data concurrently ([GPars](http://gpars.codehaus.org))
- Supports any SSH enabled device (v2+ Only)
- Independent Git Repository for each collected device
- Integration with [HP NNMi](http://www8.hp.com/us/en/software-solutions/network-node-manager-i-network-management-software/) for device discovery
- Device Profile schema validator

## Working Device Profiles
- Cisco:
  - Routers/Switchs/Nexus
- Fortinet
  - FortiGate Firewalls
- JuniperNetworks
  - Juniper
- Check Point
  - SecurePlatform Linux

## Requirements
- [Java](http://www.java.com) 1.7+
- [Gradle](http://www.gradle.org) (Only if building the project from src)

## Installation and Configuration
Take a look at the CVM [Wiki](https://github.com/syepes/cvm/wiki)

## Contribute
If you have any idea for an improvement or find a bug do not hesitate in opening an issue.
And if you have the time clone this repo and submit a pull request to help improve the CVM project.

## License
CVM is distributed under [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0).

Copyright &copy; 2014, [Sebastian YEPES F.](mailto:syepes@gmail.com)

## Used open source projects
[Groovy](http://groovy.codehaus.org) |
[GPars](http://gpars.codehaus.org) |
[Logback](http://logback.qos.ch) |
[JSch](http://www.jcraft.com/jsch) |
[ExpectIt](https://github.com/Alexey1Gavrilov/ExpectIt) |
[jGIT](http://www.eclipse.org/jgit) |
[groovy-wslite](https://github.com/jwagenleitner/groovy-wslite) |
[json-schema-validator](https://github.com/fge/json-schema-validator)


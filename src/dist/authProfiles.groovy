authProfiles = [[type:"vendor", pattern:"~/(?i)cisco.*/", auth:['cisco','secret']],
                [type:"vendor", pattern:"~/(?i)fortinet.*/", auth:['admin','admin']],
                [type:"vendor", pattern:"~/(?i)juniper.*/", auth:['netscreen','netscreen']],
                [type:"device", pattern:"~/(?i)dc.-core.*/", auth:['admin','secret']]
               ]

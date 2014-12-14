authProfiles = [[type:"vendor", pattern:"~/(?i)cisco.*/", auth:['cisco','secret']],
                [type:"device", pattern:"~/(?i)dc1-ro.*/", auth:['admin','secret','enablePasswd']],
                [type:"vendor", pattern:"~/(?i).*fortinet.*/", auth:['admin','admin']],
                [type:"vendor", pattern:"~/(?i)juniper.*/", auth:['netscreen','netscreen']],
                [type:"device", pattern:"~/(?i)fw-core.*/", auth:['admin','secret','expertPasswd']]
               ]

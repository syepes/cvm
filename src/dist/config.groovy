// Absolute or Relative Paths
git.repo                      = $/repository/$
deviceProfilePath             = $/profiles/$
authProfilePath               = $/authProfiles.groovy/$

deviceSource.src              = 'NNMi' // file, NNMi

// deviceSource.src = 'file'
//deviceSource.file_path        = $//opt/cvm/deviceList.groovy/$

// deviceSource.src = 'NNMi'
deviceSource.nnmi_vip         = 'nnm.domain.com' // NNMi VIP
deviceSource.nnmi_usr         = 'system'
deviceSource.nnmi_pwd         = 'system'
deviceSource.nnmi_deviceTypes = ['router','switchrouter','switch','firewall','wirelessaccesspoint']
deviceSource.nnmi_nodeGroup   = 'Topology group' // NodeGroup or null


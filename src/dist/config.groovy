// Paths can be either Absolute or Relative
git.repo                      = $/repository/$
authProfileConfig             = $/authProfiles.groovy/$
deviceProfileConfig           = $/deviceProfiles.groovy/$
deviceProfilePath             = $/profiles/$

deviceSource.src = 'file'
deviceSource.file_path        = $//opt/cvm/deviceList.groovy/$

// HP NNMi Integration
//deviceSource.src              = 'NNMi' // file, NNMi
//deviceSource.nnmi_vip         = 'nnm.domain.com' // NNMi VIP
//deviceSource.nnmi_usr         = 'system'
//deviceSource.nnmi_pwd         = 'system'
//deviceSource.nnmi_deviceTypes = ['router','switchrouter','switch','firewall','wirelessaccesspoint']
//deviceSource.nnmi_nodeGroup   = 'Topology group' // NodeGroup or null


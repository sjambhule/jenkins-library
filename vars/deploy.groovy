import com.sap.piper.CloudPlatform
import com.sap.piper.DeploymentType
import com.sap.piper.k8s.ContainerMap
import com.sap.piper.ConfigurationHelper
import com.sap.piper.Utils
import com.sap.piper.JenkinsUtils

import groovy.transform.Field

import static com.sap.piper.Prerequisites.checkScript

@Field String STEP_NAME = getClass().getName()

@Field Set GENERAL_CONFIG_KEYS = [
     /** Defines the targets to deploy on cloudFoundry or neo.*/
    'cfTargets',
    'neoTargets'
]

@Field Set STEP_CONFIG_KEYS = []

@Field Set PARAMETER_KEYS = GENERAL_CONFIG_KEYS.plus([
     'stage',
     /** Defines the deployment type */
     'enableZeroDowntimeDeployment',
    /** only for neo deployment*/
    'source'
])


void call(parameters = [:]) {

    handlePipelineStepErrors(stepName: STEP_NAME, stepParameters: parameters) {

        def index = 1
        def deployments = [:]
        def stageName = env.STAGE_NAME
        def enableZeroDowntimeDeployment = parameters.enableZeroDowntimeDeployment

        def script = checkScript(this, parameters) ?: this

        def utils = parameters.utils ?: new Utils()
        def jenkinsUtils = parameters.jenkinsUtils ?: new JenkinsUtils()
 
        Map config = ConfigurationHelper.newInstance(this)
            .loadStepDefaults()
            .mixinGeneralConfig(script.commonPipelineEnvironment, GENERAL_CONFIG_KEYS)
            .mixin(parameters, PARAMETER_KEYS)
            .use()

        utils.pushToSWA([
            step: STEP_NAME,
            stepParamKey1: 'cfTargets',
            stepParam1: config.cfTargets,
            stepParamKey2: 'neoTargets',
            stepParam2: config.neoTargets
        ], config)

        if (config.cfTargets) {
            for (int i = 0; i < config.cfTargets.size(); i++) {
                def target = config.cfTargets[i]
                Closure deployment = {
                    utils.unstash(stageName)

                    String deploymentType
                    if(enableZeroDowntimeDeployment) {
                        deploymentType = DeploymentType.CF_BLUE_GREEN.toString()
                    }
                    else {
                        deploymentType = DeploymentType.selectFor(
                            CloudPlatform.CLOUD_FOUNDRY,
                            config.isProduction.asBoolean()
                        ).toString()
                    }

                    def deployTool =
                        (script.commonPipelineEnvironment.configuration.isMta) ? 'mtaDeployPlugin' : 'cf_native'

                    cloudFoundryDeploy(
                        script: script,
                        juStabUtils: utils,
                        jenkinsUtilsStub: jenkinsUtils,
                        deployType: deploymentType,
                        cloudFoundry: target,
                        mtaPath: script.commonPipelineEnvironment.mtarFilePath,
                        deployTool: deployTool
                    )

                    utils.stash(stageName)
                }
                deployments["Deployment ${index > 1 ? index : ''}"] = {
                    if (env.POD_NAME) {
                        dockerExecuteOnKubernetes(script: script, containerMap: ContainerMap.instance.getMap().get(stageName) ?: [:]) {
                            deployment.run()
                        }
                    } else {
                        node(env.NODE_NAME) {
                            deployment.run()
                        }
                    }
                }
                index++
            }
            utils.runClosures(deployments)
        }
        if (config.neoTargets) {
            def source
            if (config.source) {
                source = config.source
            } else {
                def pom = readMavenPom file: 'application/pom.xml'
                source = "application/target/${pom.getArtifactId()}.${pom.getPackaging()}"
            }

            for (int i = 0; i < config.neoTargets.size(); i++) {
                def target = config.neoTargets[i]

                Closure deployment = {
                    utils.unstash(stageName)

                    DeploymentType deploymentType
                    if(enableZeroDowntimeDeployment) {
                        deploymentType = DeploymentType.NEO_ROLLING_UPDATE
                    }
                    else {
                        deploymentType = DeploymentType.selectFor(CloudPlatform.NEO, config.isProduction.asBoolean())
                    }

                    neoDeploy (
                        script: script,
                        warAction: deploymentType.toString(),
                        source: source,
                        neo: target
                    )

                    utils.stash(stageName)
                }
                deployments["Deployment ${index > 1 ? index : ''}"] = {
                    if (env.POD_NAME) {
                        dockerExecuteOnKubernetes(script: script, containerMap: ContainerMap.instance.getMap().get(stageName) ?: [:]) {
                            deployment.run()
                        }
                    } else {
                        node(env.NODE_NAME) {
                            deployment.run()
                        }
                    }
                }
                index++
            }
            utils.runClosures(deployments)
        }
        if (!config.cfTargets && !config.neoTargets)
        {
            currentBuild.result = 'FAILURE'
            error "Test Deployment skipped because no targets defined!"
        }
    }
}

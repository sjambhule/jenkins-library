import com.sap.piper.JenkinsUtils
import com.sap.piper.Utils

import hudson.AbortException

import org.jenkinsci.plugins.credentialsbinding.impl.CredentialNotFoundException

import org.junit.Assert
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

import util.*

import static org.junit.Assert.assertThat

import static org.hamcrest.Matchers.hasEntry
import static org.hamcrest.Matchers.hasItem
import static org.hamcrest.Matchers.containsString


class DeployTest extends BasePiperTest {

    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder()

    private ExpectedException thrown = new ExpectedException().none()
    private JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
    private JenkinsShellCallRule shellRule = new JenkinsShellCallRule(this)
    private JenkinsCredentialsRule credentialsRule = new JenkinsCredentialsRule(this)
    private JenkinsStepRule stepRule = new JenkinsStepRule(this)
    private JenkinsLockRule lockRule = new JenkinsLockRule(this)
    private JenkinsReadYamlRule readYamlRule = new JenkinsReadYamlRule(this)
    private JenkinsWriteFileRule writeFileRule = new JenkinsWriteFileRule(this)
    private JenkinsDockerExecuteRule dockerExecuteRule = new JenkinsDockerExecuteRule(this)
    private JenkinsFileExistsRule fileExistsRule = new JenkinsFileExistsRule(this, ['file.mtar', 'file.war', 'file.properties'])


    class JenkinsUtilsMock extends JenkinsUtils {
        def isJobStartedByUser() {
            return true
        }
    }

    class UtilsMock extends Utils {
        @Override
        def stash(name, include = '**/*.*', exclude = '') {
            return
        }

        @Override
        def unstash(name, msg = "Unstash failed:") {
            return
        }

        @Override
        def unstashAll(stashContent) {
            return
        }
    }

    private UtilsMock utilsMock = new UtilsMock()
    private JenkinsUtilsMock jenkinsUtilsMock = new JenkinsUtilsMock()

    @Rule
    public RuleChain ruleChain = Rules
        .getCommonRules(this)
        .around(readYamlRule)
        .around(thrown)
        .around(loggingRule)
        .around(shellRule)
        .around(credentialsRule)
        .around(lockRule)
        .around(new JenkinsWithEnvRule(this))
        .around(writeFileRule)
        .around(fileExistsRule)
        .around(dockerExecuteRule)
        .around(stepRule)


    @Before
    void init() {

        helper.registerAllowedMethod('pwd', [], { return "${tmp.getRoot()}" })
        helper.registerAllowedMethod('deleteDir', [], null)

        credentialsRule
            .withCredentials('credentialsId1', 'user1', 'password1')
            .withCredentials('credentialsId2', 'user2', 'password2')
            .withCredentials('paramNeoCredentialsId', 'paramNeoUser', 'paramNeoPassword')
            .withCredentials('CI_CREDENTIALS_ID', 'defaultUser', '********')
            .withCredentials('cfCredentialsId1', 'cfUser1', 'cfPassword1')
            .withCredentials('cfCredentialsId2', 'cfUser2', 'cfPassword2')
            .withCredentials('paramCfCredentialsId', 'paramCfUser', 'paramCfPassword')

        readYamlRule.registerYaml('test.yml', 'applications: [[]]')

        nullScript.commonPipelineEnvironment.configuration = [
            general: [
                neoTargets: [
                    [
                        host: 'test.deploy.host1.com',
                        account: 'trialuser1',
                        credentialsId: 'credentialsId1'
                    ],
                    [
                        host: 'test.deploy.host2.com',
                        account: 'trialuser2',
                        credentialsId: 'credentialsId2'
                    ]
                ],
                cfTargets: [
                    [
                        appName:'testAppName1',
                        manifest: 'test.yml',
                        org: 'testOrg1',
                        space: 'testSpace1',
                        credentialsId: 'cfCredentialsId1'
                    ],
                    [
                        appName:'testAppName2',
                        manifest: 'test.yml',
                        org: 'testOrg2',
                        space: 'testSpace2',
                        credentialsId: 'cfCredentialsId2'
                    ]
                ]
            ],
            stages: [
                acceptance: [
                    org: 'testOrg',
                    space: 'testSpace',
                    deployUser: 'testUser'
                ]
            ],
            steps: [
                cloudFoundryDeploy: [
                    deployTool: 'cf_native',
                    deployType: 'blue-green',
                    keepOldInstance: true,
                    cf_native: [
                        dockerImage: 's4sdk/docker-cf-cli',
                        dockerWorkspace: '/home/piper'
                    ]
                ]
            ]
        ]
    }

    @Test
    void errorNoTargetsDefined() {

        nullScript.commonPipelineEnvironment.configuration = [
            general: [
                neoTargets: [],
                cfTargets: []
            ]
        ]

        thrown.expect(Exception)
        thrown.expectMessage('Deployment skipped because no targets defined!')

        stepRule.step.deploy(
            script: nullScript
        )
    }

    @Test
    void neoDeploymentTest() {

        nullScript.commonPipelineEnvironment.configuration.general.cfTargets = []

        stepRule.step.deploy(
            script: nullScript,
            utils: utilsMock,
            stage: 'test',
            source: 'file.mtar'
        )

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog('neo.sh deploy-mta')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host1\\.com')
                .hasSingleQuotedOption('account', 'trialuser1')
                .hasSingleQuotedOption('user', 'user1')
                .hasSingleQuotedOption('password', 'password1')
                .hasSingleQuotedOption('source', 'file.mtar'))

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog('neo.sh deploy-mta')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host2\\.com')
                .hasSingleQuotedOption('account', 'trialuser2')
                .hasSingleQuotedOption('user', 'user2')
                .hasSingleQuotedOption('password', 'password2')
                .hasSingleQuotedOption('source', 'file.mtar'))
    }

    @Test
    void neoRollingUpdateTest() {

        stepRule.step.deploy(
            script: nullScript,
            utils: utilsMock,
            jenkinsUtils: jenkinsUtilsMock,
            stage: 'test',
            neoTargets: [
                [
                    host: 'test.param.deploy.host.com',
                    account: 'trialparamNeoUser',
                    credentialsId: 'paramNeoCredentialsId'
                ]
            ],
            source: 'file.mtar',
            enableZeroDowntimeDeployment: true
        )

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog('neo.sh deploy-mta')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('host', 'test\\.param\\.deploy\\.host\\.com')
                .hasSingleQuotedOption('account', 'trialparamNeoUser')
                .hasSingleQuotedOption('user', 'paramNeoUser')
                .hasSingleQuotedOption('password', 'paramNeoPassword')
                .hasSingleQuotedOption('source', 'file.mtar'))

        assertThat(shellRule.shell, hasItem(containsString('cf login -u "cfUser1" -p \'cfPassword1\' -a https://api.cf.eu10.hana.ondemand.com -o "testOrg1" -s "testSpace1"')))
        assertThat(shellRule.shell, hasItem(containsString("cf blue-green-deploy testAppName1 -f 'test.yml'")))
        assertThat(shellRule.shell, hasItem(containsString("cf logout")))
    }

    @Test
    void cfDeploymentTest() {

        nullScript.commonPipelineEnvironment.configuration.general.neoTargets = []

        stepRule.step.deploy([
            script: nullScript,
            utils: utilsMock,
            jenkinsUtils: jenkinsUtilsMock,
            stage: 'acceptance',
            cfTargets: [
                [
                    appName:'paramTestAppName',
                    manifest: 'test.yml',
                    org: 'paramTestOrg',
                    space: 'paramTestSpace',
                    credentialsId: 'paramCfCredentialsId'
                ]
            ]
        ])

        assertThat(dockerExecuteRule.dockerParams, hasEntry('dockerImage', 's4sdk/docker-cf-cli'))
        assertThat(dockerExecuteRule.dockerParams, hasEntry('dockerWorkspace', '/home/piper'))

        assertThat(shellRule.shell, hasItem(containsString('cf login -u "paramCfUser" -p \'paramCfPassword\' -a https://api.cf.eu10.hana.ondemand.com -o "paramTestOrg" -s "paramTestSpace"')))
        assertThat(shellRule.shell, hasItem(containsString("cf push paramTestAppName -f 'test.yml'")))
        assertThat(shellRule.shell, hasItem(containsString("cf logout")))
    }

    @Test
    void cfBlueGreenDeploymentTest() {

        nullScript.commonPipelineEnvironment.configuration.general.neoTargets = []

        stepRule.step.deploy([
            script: nullScript,
            utils: utilsMock,
            jenkinsUtils: jenkinsUtilsMock,
            stage: 'acceptance',
            enableZeroDowntimeDeployment: true
        ])

        assertThat(dockerExecuteRule.dockerParams, hasEntry('dockerImage', 's4sdk/docker-cf-cli'))
        assertThat(dockerExecuteRule.dockerParams, hasEntry('dockerWorkspace', '/home/piper'))

        assertThat(shellRule.shell, hasItem(containsString('cf login -u "cfUser1" -p \'cfPassword1\' -a https://api.cf.eu10.hana.ondemand.com -o "testOrg1" -s "testSpace1"')))
        assertThat(shellRule.shell, hasItem(containsString("cf blue-green-deploy testAppName1 -f 'test.yml'")))
        assertThat(shellRule.shell, hasItem(containsString("cf logout")))

        assertThat(shellRule.shell, hasItem(containsString('cf login -u "cfUser2" -p \'cfPassword2\' -a https://api.cf.eu10.hana.ondemand.com -o "testOrg2" -s "testSpace2"')))
        assertThat(shellRule.shell, hasItem(containsString("cf blue-green-deploy testAppName2 -f 'test.yml'")))
        assertThat(shellRule.shell, hasItem(containsString("cf logout")))
    }

    @Test
    void multicloudDeploymentTest() {

        stepRule.step.deploy([
            script: nullScript,
            utils: utilsMock,
            jenkinsUtils: jenkinsUtilsMock,
            stage: 'acceptance',
            enableZeroDowntimeDeployment: true,
            source: 'file.mtar'
        ])

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog('neo.sh deploy-mta')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host1\\.com')
                .hasSingleQuotedOption('account', 'trialuser1')
                .hasSingleQuotedOption('user', 'user1')
                .hasSingleQuotedOption('password', 'password1')
                .hasSingleQuotedOption('source', 'file.mtar'))

        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher().hasProlog('neo.sh deploy-mta')
                .hasOption('synchronous', '')
                .hasSingleQuotedOption('host', 'test\\.deploy\\.host2\\.com')
                .hasSingleQuotedOption('account', 'trialuser2')
                .hasSingleQuotedOption('user', 'user2')
                .hasSingleQuotedOption('password', 'password2')
                .hasSingleQuotedOption('source', 'file.mtar'))

        assertThat(shellRule.shell, hasItem(containsString('cf login -u "cfUser1" -p \'cfPassword1\' -a https://api.cf.eu10.hana.ondemand.com -o "testOrg1" -s "testSpace1"')))
        assertThat(shellRule.shell, hasItem(containsString("cf blue-green-deploy testAppName1 -f 'test.yml'")))
        assertThat(shellRule.shell, hasItem(containsString("cf logout")))

        assertThat(shellRule.shell, hasItem(containsString('cf login -u "cfUser2" -p \'cfPassword2\' -a https://api.cf.eu10.hana.ondemand.com -o "testOrg2" -s "testSpace2"')))
        assertThat(shellRule.shell, hasItem(containsString("cf blue-green-deploy testAppName2 -f 'test.yml'")))
        assertThat(shellRule.shell, hasItem(containsString("cf logout")))
    }

}

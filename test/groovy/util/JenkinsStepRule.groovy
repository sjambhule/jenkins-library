package util

import com.lesfurets.jenkins.unit.BasePipelineTest

import java.beans.Introspector

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement


class JenkinsStepRule implements TestRule {
    final BasePipelineTest testInstance
    final String stepName
    def step
    def callsIndex = 0
    def callsParameters = [:]

    JenkinsStepRule(BasePipelineTest testInstance) {
        this.testInstance = testInstance
    }

    JenkinsStepRule(BasePipelineTest testInstance, String stepName) {
        this.testInstance = testInstance
        this.stepName = stepName
    }

    boolean hasParameter(def key, def value){
        for ( def parameters : callsParameters) {
            for ( def parameter : parameters.value.entrySet()) {
                if (parameter.key.equals(key) && parameter.value.equals(value)) return true
            }
        }
        return false
    }

    @Override
    Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            void evaluate() throws Throwable {

                if (!stepName) {
                    def testClassName = testInstance.getClass().getSimpleName()
                    def stepName = Introspector.decapitalize(testClassName.replaceAll('Test$', ''))
                    this.step = testInstance.loadScript("${stepName}.groovy")
                } else {
                    testInstance.helper.registerAllowedMethod(this.stepName, [Map], { Map m ->
                        this.callsIndex += 1
                        this.callsParameters.put(callsIndex, m)
                    })
                }

                base.evaluate()
            }
        }
    }
}

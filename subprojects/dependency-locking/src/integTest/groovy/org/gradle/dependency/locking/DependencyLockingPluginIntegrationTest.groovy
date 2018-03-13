/*
 *  Copyright 2018 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.gradle.dependency.locking

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest

import static org.gradle.util.Matchers.containsText

class DependencyLockingPluginIntegrationTest extends AbstractDependencyResolutionTest {

    def lockfileFixture = new LockfileFixture(testDirectory: testDirectory)

    def 'succeeds with lock file present'() {
        mavenRepo.module('org', 'foo', '1.0').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    lockedConf 'org:foo:1.+'
}
"""

        lockfileFixture.createLockfile('lockedConf',['org:foo:1.0'])

        expect:
        succeeds 'dependencies'
    }

    def 'succeeds without lock file present and does not create one'() {
        mavenRepo.module('org', 'foo', '1.0').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    unlockedConf
}

dependencies {
    unlockedConf 'org:foo:1.+'
}
"""

        when:
        succeeds 'dependencies'

        then:
        lockfileFixture.expectMissing('unlockedConf')
    }

    def 'fails with out-of-date lock file'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'foo', '1.1').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    constraints {
        lockedConf('org:foo') {
            version {
                prefer '1.1'
            }
        }
    }
    lockedConf 'org:foo:1.+'
}
"""

        lockfileFixture.createLockfile('lockedConf',['org:foo:1.0'])

        when:
        fails 'dependencies'

        then:
//        failure.assertThatCause(containsText(LockOutOfDateException.class.name)) // Fails to find the exception name in the cause
        failure.assertThatCause(containsText("Lock file expected 'org:foo:1.0' but resolution result was 'org:foo:1.1'"))
    }

    def 'fails when lock file entry not resolved'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'bar', '1.0').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    lockedConf 'org:foo:1.+'
}
"""

        lockfileFixture.createLockfile('lockedConf',['org:bar:1.0', 'org:foo:1.0'])

        when:
        fails 'dependencies'

        then:
        failure.assertThatCause(containsText("Lock file contained 'org:bar:1.0' but it is not part of the resolved modules"))
    }

    def 'writes dependency lock file when requested'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'bar', '1.0').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    lockedConf 'org:foo:1.+'
    lockedConf 'org:bar:1.+'
}
"""

        when:
        succeeds'dependencies', 'saveDependencyLocks'

        then:
        lockfileFixture.verifyLockfile('lockedConf', ['org:bar:1.0', 'org:foo:1.0'])

    }

    def 'allows --upgradeAllLocks option to task'() {
        buildFile << """
apply plugin: 'dependency-locking'
"""
        expect:
        succeeds 'saveDependencyLocks', '--upgradeAllLocks'
    }

    def 'allows --upgradeModuleLocks option to task'() {
        buildFile << """
apply plugin: 'dependency-locking'
"""
        expect:
        succeeds 'saveDependencyLocks', '--upgradeModuleLocks', 'org:foo'
    }

    def 'upgrades lock file'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'foo', '1.1').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    constraints {
        lockedConf('org:foo') {
            version {
                prefer '1.1'
            }
        }
    }
    lockedConf 'org:foo:1.+'
}
"""

        file('gradle', 'dependency-locks', 'lockedConf.lockfile') << """
org:foo:1.0
"""
        when:
        succeeds 'dependencies', 'saveDependencyLocks', '--upgradeAllLocks'

        then:
        lockfileFixture.verifyLockfile('lockedConf', ['org:foo:1.1'])
    }

    def 'partially upgrades lock file'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'foo', '1.1').publish()
        mavenRepo.module('org', 'bar', '1.0').publish()
        mavenRepo.module('org', 'bar', '1.1').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    constraints {
        lockedConf('org:foo') {
            version {
                prefer '1.1'
            }
        }
    }
    lockedConf 'org:foo:[1.0, 1.1]'
    lockedConf 'org:bar:[1.0, 1.1]'
}
"""

        file('gradle', 'dependency-locks', 'lockedConf.lockfile') << """
org:bar:1.0
org:foo:1.0
"""
        when:
        succeeds 'dependencies', 'saveDependencyLocks', '--upgradeModuleLocks', 'org:foo'

        then:
        lockfileFixture.verifyLockfile('lockedConf', ['org:bar:1.0', 'org:foo:1.1'])
    }

    def 'fails and details all out lock issues'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'foo', '1.1').publish()
        mavenRepo.module('org', 'bar', '1.0').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    constraints {
        lockedConf('org:foo') {
            version {
                prefer '1.1'
            }
        }
    }
    lockedConf 'org:foo:[1.0, 1.1]'
}
"""

        lockfileFixture.createLockfile('lockedConf',['org:bar:1.0', 'org:foo:1.0'])

        when:
        fails 'dependencies'

        then:
        failure.assertHasCause("Dependency lock out of date:\n" +
            "\tLock file contained 'org:bar:1.0' but it is not part of the resolved modules\n" +
            "\tLock file expected 'org:foo:1.0' but resolution result was 'org:foo:1.1'")
    }

    def 'fails in strict mode when new dependencies appear'() {
        mavenRepo.module('org', 'foo', '1.0').publish()
        mavenRepo.module('org', 'bar', '1.0').publish()

        buildFile << """
apply plugin: 'dependency-locking'

repositories {
    maven {
        name 'repo'
        url '${mavenRepo.uri}'
    }
}
configurations {
    lockedConf
}

dependencies {
    lockedConf 'org:foo:1.+'
    lockedConf 'org:bar:1.+'
}
"""

        lockfileFixture.createLockfile('lockedConf',['org:foo:1.0'])

        when:
        fails 'dependencies'

        then:
        failure.assertHasCause("Dependency lock out of date (strict mode):\n" +
            "Module missing from lock file: org:bar:1.0")
    }

}

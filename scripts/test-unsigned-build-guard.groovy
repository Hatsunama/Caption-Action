// Execute the real guard against controlled Gradle/AGP boundaries, without
// launching Gradle, generating keys, or executing any Android tasks.
import org.gradle.api.GradleException

def guard = new File(args[0]).text
def finalizerType = new GroovyClassLoader(this.class.classLoader).parseClass('''
package com.android.build.gradle.internal.tasks
class FinalizeBundleTask {
    String name
    String path
    Object signingConfigData
    Object codeTransparencySigningConfigData
}
''')
def providerType = new GroovyClassLoader(this.class.classLoader).parseClass('''
package com.android.build.gradle.internal.signing
class SigningConfigDataProvider {
    Object signingConfigData
    Object signingConfigFileCollection
    Object resolve() { throw new AssertionError('Never resolve/read signing files') }
}
''')
int passed = 0
[
    'unsigned', 'metadata', 'finalizer-empty-provider', 'finalizer-data-provider', 'finalizer-file-provider', 'finalizer-unknown-provider', 'finalizer-unsigned', 'finalizer-signed', 'finalizer-transparency', 'finalizer-wrong-path', 'dsl-signed', 'debug-signed', 'test-signed', 'release-signed',
    'signing-ready', 'fallback-enabled', 'injected-signing', 'agp-upgrade',
    'missing-test', 'configuration-cache', 'validateSigningDebug', 'signRelease', 'signReleaseBundle', 'createDebugKeystore',
    'generateDebugKey', 'keytool', 'connectedDebugAndroidTest', 'installDebug'
].each { scenario ->
    def callbacks = [:]
    def props = ['android.experimental.useDefaultDebugSigningConfigForProfileableBuildtypes': 'false']
    if (scenario == 'fallback-enabled') props[props.keySet().first()] = 'true'
    if (scenario == 'injected-signing') props['android.injected.signing.store.file'] = 'fixture-not-opened'
    def variants = ['debug', 'release', 'debugAndroidTest'].collect { name ->
        def signed = (scenario == 'debug-signed' && name == 'debug') ||
                     (scenario == 'test-signed' && name == 'debugAndroidTest') ||
                     (scenario == 'release-signed' && name == 'release')
        [name: name, applicationId: 'fixture.' + name, signingConfig: signed ? 'debug' : null,
         isSigningReady: { signed || scenario == 'signing-ready' }]
    }
    def container = { rows -> [all: { Closure closure -> rows.each(closure) }] }
    def android = [buildTypes: container(['debug', 'release'].collect { [name: it, signingConfig: scenario == 'dsl-signed' ? 'debug' : null] }),
                   applicationVariants: container(variants.take(2)),
                   testVariants: container(scenario == 'missing-test' ? [] : variants.drop(2))]
    def components = [pluginVersion: [version: scenario == 'agp-upgrade' ? '9.0.0' : '8.10.1']]
    def project = [extensions: [getByName: { name -> name == 'android' ? android : components }],
                   properties: props, findProperty: { key -> props[key] }]
    def gradle = [projectsEvaluated: { Closure c -> callbacks.projects = c },
                  taskGraph: [whenReady: { Closure c -> callbacks.graph = c }], startParameter: [dryRun: true, configurationCacheRequested: scenario == 'configuration-cache']]
    def allowed = scenario in ['unsigned', 'metadata', 'finalizer-empty-provider', 'finalizer-unsigned']
    def taskName = scenario == 'metadata' ? 'writeDebugSigningConfigVersions' :
                   (scenario in ['validateSigningDebug', 'signRelease', 'signReleaseBundle', 'createDebugKeystore', 'generateDebugKey', 'keytool', 'connectedDebugAndroidTest', 'installDebug'] ? scenario : 'assembleDebug')
    def task = new Expando(name: taskName, path: ':app:' + taskName)
    if (scenario.startsWith('finalizer-')) {
        task = finalizerType.newInstance(name: 'signReleaseBundle',
            path: scenario == 'finalizer-wrong-path' ? ':other:signReleaseBundle' : ':app:signReleaseBundle',
            signingConfigData: scenario == 'finalizer-signed' ? new Object() : null,
            codeTransparencySigningConfigData: scenario == 'finalizer-transparency' ? new Object() : null)
    }
    if (scenario in ['finalizer-empty-provider', 'finalizer-data-provider', 'finalizer-file-provider']) {
        task.signingConfigData = providerType.newInstance(
            signingConfigData: [orNull: scenario == 'finalizer-data-provider' ? new Object() : null],
            signingConfigFileCollection: scenario == 'finalizer-file-provider' ? new Object() : null)
    }
    if (scenario == 'finalizer-unknown-provider') {
        task.signingConfigData = [signingConfigData: [orNull: null], signingConfigFileCollection: null]
    }
    boolean rejected = false
    try {
        new GroovyShell(this.class.classLoader, new Binding([out: new PrintWriter(new StringWriter()), project: project, gradle: gradle, tasks: [register: { String name, Class type, Closure c -> assert name == "testUnsignedBuildGuard" }]])).evaluate(guard)
        callbacks.projects.call()
        callbacks.graph.call([allTasks: [task]])
    } catch (GradleException expected) {
        rejected = true
        println('REJECTED ' + scenario + ': ' + expected.message)
    }
    assert rejected != allowed : 'Unexpected guard result for ' + scenario
    passed++
}
println('Unsigned guard policy fixtures passed: ' + passed)

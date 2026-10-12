// Run the actual production guard against resolved AGP identities; never build/sign/install.
import org.gradle.api.GradleException

def guard = new File(args[0]).text
int failures = 0
int passed = 0
[
    [name:'default identities', property:null, debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.debug.test', allowed:true],
    [name:'explicit false defaults', property:'false', debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.debug.test', allowed:true],
    [name:'isolated QA identities', property:'true', debug:'com.hatsunama.captionaction.qa', test:'com.hatsunama.captionaction.qa.test', allowed:true],
    [name:'opt-in cannot retain installed debug', property:'true', debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.debug.test', allowed:false],
    [name:'QA cannot use production app', property:'true', debug:'com.hatsunama.captionaction', test:'com.hatsunama.captionaction.qa.test', allowed:false],
    [name:'QA test cannot retain installed debug test', property:'true', debug:'com.hatsunama.captionaction.qa', test:'com.hatsunama.captionaction.debug.test', allowed:false],
    [name:'default cannot silently use QA app', property:null, debug:'com.hatsunama.captionaction.qa', test:'com.hatsunama.captionaction.qa.test', allowed:false],
    [name:'QA must preserve release identity', property:'true', debug:'com.hatsunama.captionaction.qa', test:'com.hatsunama.captionaction.qa.test', release:'com.hatsunama.captionaction.qa', allowed:false],
    [name:'default test identity must be isolated', property:null, debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.test', allowed:false],
    [name:'invalid property rejected', property:'yes', debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.debug.test', allowed:false],
    [name:'empty property rejected', property:'', debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.debug.test', allowed:false],
    [name:'case typo rejected', property:'TRUE', debug:'com.hatsunama.captionaction.debug', test:'com.hatsunama.captionaction.debug.test', allowed:false]
].each { scenario ->
    def callbacks=[:]
    def props=['android.experimental.useDefaultDebugSigningConfigForProfileableBuildtypes':'false']
    if(scenario.property!=null) props['caption.isolatedQa']=scenario.property
    def ids=[debug:scenario.debug, release:scenario.release ?: 'com.hatsunama.captionaction', debugAndroidTest:scenario.test]
    def variants=ids.collect { name,id -> [name:name, applicationId:id, signingConfig:null,isSigningReady:{false}] }
    def container={ rows -> [all:{Closure c -> rows.each(c)}] }
    def android=[buildTypes:container(['debug','release'].collect{[name:it,signingConfig:null]}),applicationVariants:container(variants.take(2)),testVariants:container(variants.drop(2))]
    def project=[extensions:[getByName:{name -> name=='android' ? android : [pluginVersion:[version:'8.10.1']]}],properties:props,findProperty:{key -> props[key]}]
    def gradle=[projectsEvaluated:{Closure c -> callbacks.projects=c},taskGraph:[whenReady:{Closure c -> callbacks.graph=c}],startParameter:[dryRun:true,configurationCacheRequested:false]]
    boolean rejected=false
    try {
        new GroovyShell(this.class.classLoader,new Binding([out:new PrintWriter(new StringWriter()),project:project,gradle:gradle,tasks:[register:{String name,Class type,Closure c -> assert name in ['testUnsignedBuildGuard', 'testQaIdentityGuard']}]])).evaluate(guard)
        callbacks.projects.call();callbacks.graph.call([allTasks:[new Expando(name:'assembleDebug',path:':app:assembleDebug')]])
    } catch(GradleException expected) { rejected=true }
    if(rejected == !scenario.allowed) { passed++;println('PASS '+scenario.name) }
    else { failures++;println('FAIL '+scenario.name+' expected rejection='+!scenario.allowed+' actual='+rejected) }
}
println('QA identity guard cases: '+passed+' passed, '+failures+' failed')
assert failures==0

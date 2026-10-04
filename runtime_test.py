"""Production Mixin smoke test using only Gradle-resolved dependencies (no world)."""
import json, os, subprocess, sys, zipfile, shutil, re
from pathlib import Path

root=Path(__file__).resolve().parent
out=root/'build'
if len(sys.argv)!=2:
    raise SystemExit('Use gradlew productionMixinSmoke; Gradle supplies production-runtime.json')
config=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
run=out/'v2-runtime'
run.mkdir(parents=True,exist_ok=True)
(run/'mods').mkdir(exist_ok=True)
cp=os.pathsep.join(config['classpath'])
java_bin=Path(config['javaHome'])/'bin'
def tool(name): return str(java_bin/(name+('.exe' if os.name=='nt' else '')))
def argfile(path,args):
    path.write_text('\n'.join('"'+str(a).replace('\\','/').replace('"','\\"')+'"' for a in args),encoding='utf-8')
classes=out/'launch-classes'
classes.mkdir(exist_ok=True)
args=['--release','17','-proc:none','-cp',cp,'-d',str(classes),str(root/'tests/runtime/HeadlessLaunch.java')]
argfile(out/'launch-javac.args',args)
subprocess.run([tool('javac'),'@'+str(out/'launch-javac.args')],check=True)
launcher=out/'spawnselector-test-launcher.jar'
with zipfile.ZipFile(launcher,'w') as jar:
    for p in classes.rglob('*.class'): jar.write(p,p.relative_to(classes).as_posix())
    jar.writestr('META-INF/services/cpw.mods.modlauncher.api.ILaunchHandlerService','dev.tide.tests.HeadlessLaunch\n')
shutil.copy2(config['productionJar'],run/'mods/spawnselector.jar')
classpath=cp+os.pathsep+str(launcher)
(out/'production-legacy-classpath.txt').write_text('\n'.join(config['classpath']+[str(launcher)]),encoding='utf-8')
replacements={'{modules}':os.pathsep.join(config['modules']),
              '{minecraft_classpath_file}':str(out/'production-legacy-classpath.txt')}
jvm=['-Xmx2G','-Dmixin.debug.export=true','-Dmixin.debug.countInjections=true','-Djava.awt.headless=true']
for arg in config['jvmArgs']:
    for k,v in replacements.items(): arg=arg.replace(k,v)
    jvm.append(arg)
for key,value in config['properties'].items():
    for k,v in replacements.items(): value=value.replace(k,v)
    jvm.append('-D'+key+'='+value)
jvm+=['-cp',classpath,'cpw.mods.bootstraplauncher.BootstrapLauncher','--launchTarget','spawnselectortest',
      '--fml.forgeVersion',config['forgeVersion'],'--fml.mcVersion',config['minecraftVersion'],'--fml.forgeGroup','net.minecraftforge',
      '--fml.mcpVersion',config['mcpVersion'],'--gameDir',str(run),'--nogui']
argfile(out/'runtime.args',jvm)
with (run/'console.log').open('w',encoding='utf-8') as log:
    result=subprocess.run([tool('java'),'@'+str(out/'runtime.args')],cwd=run,stdout=log,stderr=subprocess.STDOUT,timeout=180)
print('Runtime exit:',result.returncode,'Log:',run/'console.log')
log=(run/'console.log').read_text(encoding='utf-8',errors='replace')
assert result.returncode == 0, log[-5000:]
assert 'SPAWNSELECTOR_HOOK_REGRESSION PASS' in log, log[-5000:]
checks={'net/minecraft/server/MinecraftServer':['defer','skipStart','skipWait'],
        'net/minecraft/server/level/ServerPlayer':['constructionPosition','holdingPosition'],
        'net/minecraft/server/players/PlayerList':['construction','placement','respawnPos','respawnFallback'],
        'net/minecraft/world/entity/Entity':['noHoldingCollision','noHoldingPush']}
for cls, hooks in checks.items():
    assert 'SPAWNSELECTOR_MIXIN_LOADED '+cls.replace('/','.') in log, log[-5000:]
    bytecode=subprocess.check_output([tool('javap'),'-p','-c',str(run/'.mixin.out/class'/(cls+'.class'))],text=True,encoding='utf-8',errors='replace')
    for hook in hooks:
        assert any('invoke' in line and '$'+hook+':' in line for line in bytecode.splitlines()), (cls,hook)
    (out/(cls.rsplit('/',1)[1]+'-v2-transformed.txt')).write_text(bytecode,encoding='utf-8')
print('PASS: Production SRG mixins transformed; all checked hooks have live invocation sites.')
print('PASS: Actual transformed loop/START guards and holding/normal constructor operation executed without a world.')
accessor='net/minecraft/server/level/ServerChunkCache'
assert 'SPAWNSELECTOR_MIXIN_LOADED '+accessor.replace('/','.') in log
bytecode=subprocess.check_output([tool('javap'),'-p','-c',str(run/'.mixin.out/class'/(accessor+'.class'))],text=True,encoding='utf-8',errors='replace')
invoker=re.search(r'public .*spawnselector\$requestChunk\(.*?\n(.*?)(?=\n  (?:public|private|protected)|\Z)',bytecode,re.S)
assert invoker and 'm_8456_' in invoker.group(1) and 'managedBlock' not in invoker.group(1)
print('PASS: Production nonblocking chunk invoker transformed.')
print('LIMIT: no world creation, login, Origins or chunk-count gameplay test performed.')

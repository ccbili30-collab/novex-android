#!/usr/bin/env python3
"""Launch the exported native test runtime locally without an Android SDK installation."""
import argparse, os, subprocess
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--runtime',type=Path,required=True)
p.add_argument('--java',type=Path,required=True)
p.add_argument('--bridge-directory',type=Path)
p.add_argument('--test',default='com.openminis.app.novex.domain.NovexNativeCardModelBridgeTest')
a=p.parse_args()
paths=[str(a.runtime/line) for line in (a.runtime/'classpath.txt').read_text().splitlines()]
# AGP's generated config contains CI-only absolute resource paths. This bridge uses
# Application + Config.NONE and no app resources, so it must not load that config.
for entry in paths:
    config=Path(entry)/'com/android/tools/test_config.properties'
    if config.is_file(): config.rename(config.with_suffix('.ci-properties'))
env=os.environ.copy()
if a.bridge_directory: env['NOVEX_CARD_BRIDGE_DIR']=str(a.bridge_directory)
command=[str(a.java),'-Xmx2g','-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
         '-Dhttps.proxyHost=127.0.0.1','-Dhttps.proxyPort=7897','-Dhttp.proxyHost=127.0.0.1','-Dhttp.proxyPort=7897',
         '-cp',os.pathsep.join(paths),'org.junit.runner.JUnitCore',a.test]
raise SystemExit(subprocess.call(command,env=env))

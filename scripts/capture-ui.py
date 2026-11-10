#!/usr/bin/env python3
"""Capture deterministic UI on a dedicated debug emulator with fixture_server.py running.

This intentionally seeds the emulator's history with demo content. Never run on a personal device.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
adb = os.environ.get('ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
out = root / 'artifacts/screenshots'
out.mkdir(parents=True, exist_ok=True)
base = 'http://10.0.2.2:18765'


def run(*arguments, **kwargs):
    return subprocess.check_output([adb, '-s', 'emulator-5554', *arguments], **kwargs)


def page(section, title=None):
    args = ['shell', 'am', 'start', '-n', 'com.yingxia.tv/.ui.MainActivity',
            '--es', 'qaFixture', base + '/config.json', '--ei', 'qaSection', str(section)]
    if title:
        args += ['--es', 'qaTitle', title]
    run(*args)
    time.sleep(4)


def click(label):
    run('shell', 'uiautomator', 'dump', '/sdcard/yingxia-ui.xml')
    tree = ET.fromstring(run('exec-out', 'cat', '/sdcard/yingxia-ui.xml'))
    for node in tree.iter('node'):
        if node.get('content-desc') == label or node.get('text') == label:
            coordinates = list(map(int, re.findall(r'\d+', node.get('bounds'))))
            x1, y1, x2, y2 = coordinates
            run('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(2)
            return
    raise RuntimeError('Cannot find: ' + label)


def screenshot(name):
    (out / name).write_bytes(run('exec-out', 'screencap', '-p'))
    print(name, flush=True)


page(0)
run('shell', 'am', 'force-stop', 'com.yingxia.tv')
backup = run('exec-out', 'run-as', 'com.yingxia.tv', 'cat', 'files/library.json')
state = json.loads(backup)
sub = next(s for s in state['subscriptions'] if s['origin'] == base + '/config.json')
source = dict(sub['config']['sites'][0], id=sub['id'] + '@fixture', origin=sub['origin'],
              subscription=sub['name'])
with urllib.request.urlopen('http://127.0.0.1:18765/api?ids=1') as response:
    video = json.load(response)['list'][0]
video['vod_pic'] = base + '/art/peaks.webp'
episode = dict(id='0:0', name='第1集', flag='高清线路', url=base + '/playback.mp4')
state['history'] = [dict(id=source['id'] + '@1', historyId=source['id'] + '@1',
                         name='山海之间', episode='第1集', url=episode['url'],
                         poster=video['vod_pic'], site=source, video=video, episodeInfo=episode,
                         position=4500, duration=14000, completed=False)]
data = json.dumps(state, ensure_ascii=False).encode()
run('shell', '-T', 'run-as', 'com.yingxia.tv', 'dd', 'of=files/library.json', 'bs=1',
    'count=' + str(len(data)), input=data)
page(0)
screenshot('01-home.png')
page(5)
screenshot('02-sources.png')
click('映匣影视')
screenshot('03-catalog.png')
click('山海之间')
screenshot('04-details.png')
click('▶  立即播放')
time.sleep(2)
screenshot('05-player.png')
run('shell', 'input', 'keyevent', '4')
run('shell', 'input', 'keyevent', '4')
page(2)
click('直播电视')
screenshot('06-live.png')
page(9)
screenshot('07-settings.png')
page(0)

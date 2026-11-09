#!/usr/bin/env python3
"""Local UI fixtures. No subscription or demo data is included in production builds.

Run with --source ../tvbox-silicon and import http://10.0.2.2:18765/config.json
in the Android emulator. Artwork is the original website's attributed demo artwork.
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=Path('../tvbox-silicon'))
parser.add_argument('--port', type=int, default=18765)
args = parser.parse_args()
art = args.source.resolve() / 'website/public/film'
video = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/playback.mp4'
names = [('山海之间', 'peaks'), ('霓虹小巷', 'neon'), ('极光之夜', 'aurora'),
         ('一碗人间烟火', 'bowl'), ('灯塔守望者', 'lighthouse'), ('樱花季', 'blossom'),
         ('热气球飞行家', 'balloon'), ('猫的午后', 'cat'), ('夜色城市', 'city'), ('森林瀑布', 'falls')]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        address = urlsplit(self.path)
        query = parse_qs(address.query)
        base = 'http://' + self.headers.get('Host', '10.0.2.2:' + str(args.port))

        def item(index):
            title, image = names[index % len(names)]
            return dict(vod_id=str(index + 1), vod_name=title, vod_pic=f'{base}/art/{image}.webp',
                        vod_year='2026', type_name='纪录片', vod_area='中国', vod_lang='国语',
                        vod_remarks='全2集', vod_actor='映匣本地测试', vod_director='演示片库',
                        vod_content='在山海与城市之间，寻找日常生活里的光。此片库仅用于本地界面与播放验证，图片沿用原项目官网的演示素材。',
                        vod_play_from='高清线路$$$备用线路',
                        vod_play_url=f'第1集${base}/playback.mp4#第2集${base}/playback.mp4$$$完整版${base}/playback.mp4')

        if address.path == '/config.json':
            result = dict(sites=[dict(key='fixture', name='映匣影视', type=1, api=f'{base}/api'),
                                 dict(key='cinema', name='光影影院', type=1, api=f'{base}/api'),
                                 dict(key='docs', name='纪录世界', type=1, api=f'{base}/api')],
                          lives=[dict(name='直播电视', url=f'{base}/live.m3u')])
        elif address.path == '/api':
            indices = range(len(names))
            if 'ids' in query:
                indices = [int(query['ids'][0]) - 1]
            elif 'wd' in query:
                indices = [i for i, (name, _) in enumerate(names) if query['wd'][0] in name]
            result = {'class': [dict(type_id='1', type_name='电影'), dict(type_id='2', type_name='剧集'),
                                dict(type_id='3', type_name='纪录片'), dict(type_id='4', type_name='动画')],
                      'list': [item(i) for i in indices], 'page': 1, 'pagecount': 1}
        elif address.path == '/live.m3u':
            content = '#EXTM3U\n'
            for group, channel in [('央视频道', 'CCTV-1 综合'), ('央视频道', 'CCTV-6 电影'),
                                   ('卫视频道', '湖南卫视'), ('卫视频道', '东方卫视')]:
                content += f'#EXTINF:-1 group-title="{group}",{channel}\n{base}/playback.mp4\n'
            return self.send(content.encode(), 'application/vnd.apple.mpegurl')
        elif address.path.startswith('/art/'):
            name = Path(address.path).name
            if name not in {image + '.webp' for _, image in names}:
                return self.send_error(404)
            return self.send((art / name).read_bytes(), 'image/webp')
        elif address.path == '/playback.mp4':
            return self.send(video.read_bytes(), 'video/mp4')
        else:
            return self.send_error(404)
        self.send(json.dumps(result, ensure_ascii=False).encode(), 'application/json')

    def send(self, data, content_type):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


print(f'Local fixtures: http://127.0.0.1:{args.port}/config.json', flush=True)
ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()

"""Graphviz SVG 排版校验 v2：正确解析 path d 属性。
检测：节点框重叠、xlabel 压到别的节点框、文本互相重叠。"""
import re, sys, xml.etree.ElementTree as ET

NS = '{http://www.w3.org/2000/svg}'
NUM = re.compile(r'-?\d+\.?\d*')

def num(s):
    try: return float(s)
    except: return 0.0

def geom_of(el):
    """从任意形状元素提取 (xs, ys)。path 解析 d 里的坐标对。"""
    xs, ys = [], []
    tag = el.tag.replace(NS, '')
    if tag == 'path':
        d = el.get('d') or ''
        vals = [float(v) for v in NUM.findall(d)]
        for i in range(0, len(vals) - 1, 2):
            xs.append(vals[i]); ys.append(vals[i+1])
    elif tag == 'polygon':
        for pair in (el.get('points') or '').strip().split():
            p = pair.split(',')
            if len(p) == 2: xs.append(num(p[0])); ys.append(num(p[1]))
    elif tag == 'rect':
        x, y, w, h = num(el.get('x')), num(el.get('y')), num(el.get('width')), num(el.get('height'))
        xs += [x, x+w]; ys += [y, y+h]
    elif tag == 'ellipse':
        cx, cy, rx, ry = num(el.get('cx')), num(el.get('cy')), num(el.get('rx')), num(el.get('ry'))
        xs += [cx-rx, cx+rx]; ys += [cy-ry, cy+ry]
    return xs, ys

def text_bbox(t):
    x, y = num(t.get('x')), num(t.get('y'))
    content = ''.join(t.itertext()).strip()
    fs = num(t.get('font-size')) or 14.0
    cjk = sum(1 for c in content if ord(c) > 0x2E80)
    other = len(content) - cjk
    w = cjk * fs * 1.02 + other * fs * 0.56
    anchor = t.get('text-anchor', 'start')
    x0 = x - w/2 if anchor == 'middle' else (x - w if anchor == 'end' else x)
    return (x0, y - fs*0.82, x0 + w, y + fs*0.28), content

def parse(path):
    root = ET.parse(path).getroot()
    nodes = {}   # title -> (bbox, [texts])
    clusters = {}
    for g in root.iter(f'{NS}g'):
        cls = g.get('class', '')
        if cls not in ('node', 'cluster'): continue
        tel = g.find(f'{NS}title')
        title = tel.text if tel is not None else None
        xs, ys = [], []
        for child in g:
            tg = child.tag.replace(NS, '')
            if tg in ('path', 'polygon', 'rect', 'ellipse'):
                a, b = geom_of(child); xs += a; ys += b
        if not xs: continue
        bb = (min(xs), min(ys), max(xs), max(ys))
        ts = [text_bbox(t) for t in g.findall(f'{NS}text')]
        (nodes if cls == 'node' else clusters)[title] = (bb, ts)
    return nodes, clusters

def ov(a, b, pad=1.0):
    return (min(a[2], b[2]) - max(a[0], b[0])) > pad and (min(a[3], b[3]) - max(a[1], b[1])) > pad

def inside(pt, bb):
    return bb[0] <= pt[0] <= bb[2] and bb[1] <= pt[1] <= bb[3]

def report(path):
    nodes, clusters = parse(path)
    name = path.replace('\\', '/').split('/')[-1]
    print(f'=== {name} ===')
    print(f'  nodes={len(nodes)}  clusters={len(clusters)}')
    names = list(nodes)
    bad = [(a, b) for i, a in enumerate(names) for b in names[i+1:]
           if ov(nodes[a][0], nodes[b][0])]
    print(f'  node-node overlaps : {len(bad)}' + ('   <-- PROBLEM' if bad else '   OK'))
    for a, b in bad[:12]: print(f'      ! {a} <-> {b}')
    # xlabel (text outside own box) vs any node box
    risky = []
    for n, (bb, ts) in nodes.items():
        for tb, tc in ts:
            c = ((tb[0]+tb[2])/2, (tb[1]+tb[3])/2)
            if inside(c, bb): continue          # 正常内标签
            hit = [m for m in names if m != n and ov(tb, nodes[m][0])]
            if hit: risky.append((n, tc[:34], hit[:3]))
    print(f'  xlabel covering other boxes : {len(risky)}' + ('   <-- CHECK' if risky else '   OK'))
    for n, t, h in risky[:14]: print(f'      ~ {n}.xlabel "{t}" covers {h}')
    # xlabel vs cluster frame (label sitting on a group border is ugly but tolerable)
    # text-text overlap across everything
    alltext = [(bb, tc, n) for n, (_, ts) in nodes.items() for bb, tc in ts]
    for cn, (_, ts) in clusters.items():
        alltext += [(bb, tc, f'cluster:{cn}') for bb, tc in ts]
    tt = [(a[1][:26], b[1][:26]) for i, a in enumerate(alltext) for b in alltext[i+1:]
          if ov(a[0], b[0])]
    print(f'  text-text overlaps : {len(tt)}' + ('   <-- CHECK' if tt else '   OK'))
    for a, b in tt[:14]: print(f'      x "{a}" vs "{b}"')
    ax = [v[0][0] for v in nodes.values()] + [v[0][0] for v in clusters.values()]
    ay = [v[0][1] for v in nodes.values()] + [v[0][1] for v in clusters.values()]
    bx = [v[0][2] for v in nodes.values()] + [v[0][2] for v in clusters.values()]
    by = [v[0][3] for v in nodes.values()] + [v[0][3] for v in clusters.values()]
    if ax: print(f'  content bbox: x[{min(ax):.0f},{max(bx):.0f}]  y[{min(ay):.0f},{max(by):.0f}]')
    print()
    return len(bad), len(risky), len(tt)

tot = [0, 0, 0]
for p in sys.argv[1:]:
    r = report(p)
    tot = [tot[i] + r[i] for i in range(3)]
print(f'TOTAL problems: overlaps={tot[0]}  xlabel-collisions={tot[1]}  text-collisions={tot[2]}')

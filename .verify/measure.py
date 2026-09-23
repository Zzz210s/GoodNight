"""把当前 task 页的 dump 整理成「行/文本高度」报告,用于核对行高与标题是否被裁。"""
import sys
import xml.etree.ElementTree as ET

LOCAL = sys.argv[1] if len(sys.argv) > 1 else ".verify/cur.xml"
DENSITY = float(sys.argv[2]) if len(sys.argv) > 2 else 2.625


def px(b):
    a, c = b.split("][")
    x1, y1 = (int(v) for v in a.lstrip("[").split(","))
    x2, y2 = (int(v) for v in c.rstrip("]").split(","))
    return x1, y1, x2, y2


rows = []
texts = []
for n in ET.parse(LOCAL).getroot().iter("node"):
    cls = n.get("class").split(".")[-1]
    label = n.get("text") or n.get("content-desc") or ""
    x1, y1, x2, y2 = px(n.get("bounds"))
    if cls == "TextView":
        texts.append((label[:14], y1, y2, y2 - y1, x2 - x1))
    if n.get("clickable") == "true" and x2 - x1 > 900 and y2 - y1 >= 100 and cls == "View":
        rows.append((y1, y2, y2 - y1, round((y2 - y1) / DENSITY, 1)))

rows.sort()
print("clickable 行(候选卡片):y1 y2 高度px 高度dp")
for r in rows:
    print("  ", r)
print("TextView(label, y1, y2, 高度px, 宽px)  64dp = %.0fpx" % (64 * DENSITY))
for t in texts:
    print("  ", t)

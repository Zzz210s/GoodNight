"""dump 当前 UI,按文本/描述找节点,打印可点的中心坐标(供 adb shell input tap 使用)。"""
import subprocess
import sys
import xml.etree.ElementTree as ET

W = "/sdcard/w.xml"
LOCAL = ".verify/cur.xml"


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", W], capture_output=True)
    out = subprocess.run(["adb", "exec-out", "cat", W], capture_output=True)
    open(LOCAL, "wb").write(out.stdout)


def nodes():
    return list(ET.parse(LOCAL).getroot().iter("node"))


def center(bounds):
    a, b = bounds.split("][")
    x1, y1 = (int(v) for v in a.lstrip("[").split(","))
    x2, y2 = (int(v) for v in b.rstrip("]").split(","))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    dump()
    want = sys.argv[1]
    wide = "--wide" in sys.argv
    for n in nodes():
        label = n.get("text") or n.get("content-desc")
        if label == want and (not wide or n.get("bounds").endswith("][1080,%s]" % n.get("bounds").split(",")[-1].rstrip("]"))):
            x, y = center(n.get("bounds"))
            print(x, y)
            return
    for n in nodes():
        if (n.get("text") or n.get("content-desc")) == want:
            print(*center(n.get("bounds")))
            return
    print("NOT_FOUND")


main()

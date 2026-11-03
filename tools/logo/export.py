# 확정 로고를 편집기(logo-editor.html)의 기본값과 픽셀 렌더러 그대로 그려 파일로 내보낸다.
#   tools/logo/png/              로고 PNG(라이트/다크 × 1024·512·192·96·48, 배경 투명)
#   app/src/main/res/...         스플래시 아이콘·글자 로고(라이트/다크), 작동 화면 로고, 앱 아이콘 앞면 — 화면 밀도별
# 실행: python tools/logo/export.py   (Edge 또는 Chrome이 있어야 한다. 편집기 화면을 머리 없이 띄워 그림을 받는다)
import base64, json, re, subprocess, sys, tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
EDITOR = REPO / "tools" / "logo" / "logo-editor.html"
RES = REPO / "app" / "src" / "main" / "res"
BROWSERS = [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            "/usr/bin/google-chrome", "/usr/bin/chromium"]
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# (출력 경로, 테마, dp 크기, 로고 외접원 지름 / 변) — dp 크기는 Android 규격, 비율은 아래 근거
JOBS = [(f"tools/logo/png/hearspace-logo-{t}-{n}.png", t, n, 1.0, 1) for t in ("light", "dark") for n in (1024, 512, 192, 96, 48)]
for d, s in DENSITIES.items():
    # 스플래시(Android 12 SplashScreen): 아이콘 배경 없이 288 dp, 보이는 원 지름 192 dp 안 → 로고 168 dp(시안)
    JOBS += [(f"app/src/main/res/drawable-{d}/splash_icon.png", "light", 288, 168 / 288, s),
             (f"app/src/main/res/drawable-night-{d}/splash_icon.png", "dark", 288, 168 / 288, s),
             # 작동 화면 시작 전 로고: 늘 검은 배경이라 다크, 150 dp(시안)
             (f"app/src/main/res/drawable-{d}/logo_run.png", "dark", 150, 1.0, s),
             # 앱 아이콘 앞면(어댑티브 108 dp, 보이는 원 72 dp): 로고 외접원 = 72 dp × 0.85(편집기에서 고른 아이콘 속 크기) ≤ 안전 영역 66 dp
             (f"app/src/main/res/mipmap-{d}/ic_launcher_foreground.png", "light", 108, 72 * 0.85 / 108, s)]
# 글자 로고(스플래시 아래 브랜드 200×80 dp): 글자 색 = 화면 시안의 글자 역할 색
BRAND = [(f"app/src/main/res/drawable{q}-{d}/splash_brand.png", color, s)
         for d, s in DENSITIES.items() for q, color in (("", "#25262d"), ("-night", "#e4e6ed"))]

script = """table();
{ const m = model(), out = document.createElement('pre'); out.id = 'export';
  const emit = (path, cv) => out.textContent += `OUT ${path} ${cv.toDataURL('image/png')}\\n`;
  for (const [path, theme, dp, fill, scale] of %s) {
    const cv = document.createElement('canvas'); raster(cv, m, m.fitted[theme], frame(m.c, fill), Math.round(dp * scale), true); emit(path, cv); }
  for (const [path, color, scale] of %s) {
    const cv = document.createElement('canvas'); cv.width = Math.round(200 * scale); cv.height = Math.round(80 * scale);
    const x = cv.getContext('2d'); x.scale(scale, scale); x.fillStyle = color; x.textAlign = 'center'; x.textBaseline = 'middle';
    x.font = '800 22px "Segoe UI", Roboto, sans-serif'; x.letterSpacing = '5px'; x.fillText('HEARSPACE', 100 + 2.5, 40); emit(path, cv); }
  document.body.append(out); }
</script>""" % (json.dumps(JOBS), json.dumps(BRAND))

src = EDITOR.read_text(encoding="utf-8")
anchor = "table();\n</script>"
if anchor not in src:
    sys.exit("편집기 끝부분을 찾지 못함: logo-editor.html이 바뀌었으면 이 스크립트를 맞춘다")
browser = next((b for b in BROWSERS if Path(b).is_file()), None)
if browser is None:
    sys.exit("Edge 또는 Chrome을 찾지 못함")
with tempfile.TemporaryDirectory() as tmp:
    page = Path(tmp) / "export.html"
    page.write_text(src.replace(anchor, script), encoding="utf-8")
    dom = subprocess.run([browser, "--headless=new", "--disable-gpu", "--virtual-time-budget=20000", "--dump-dom", page.as_uri()],
                         capture_output=True, timeout=600).stdout.decode("utf-8", "replace")
found = re.findall(r"OUT (\S+) data:image/png;base64,([A-Za-z0-9+/=]+)", dom)
if len(found) != len(JOBS) + len(BRAND):
    sys.exit(f"그림 {len(JOBS) + len(BRAND)}장 중 {len(found)}장만 받음")
for rel, b64 in found:
    data = base64.b64decode(b64)
    assert data[:8] == b"\x89PNG\r\n\x1a\n", rel
    out = REPO / rel
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
print(f"{len(found)}개 파일을 썼다")
